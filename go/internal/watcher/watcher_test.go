package watcher

import (
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeNotifier is a Notifier double for tests: it records every call instead
// of touching a network, and can be told to fail on demand — the only way to
// exercise SPEC rule 5 (a failed notification is retried while it still
// applies). Compare to scala/src/test/scala/watcher/FakeNotifier.test.scala.
type fakeNotifier struct {
	mu         sync.Mutex
	sent       []string
	heartbeats int
	failing    bool
}

func (f *fakeNotifier) Notify(content string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failing {
		return false
	}
	f.sent = append(f.sent, content)
	return true
}

func (f *fakeNotifier) Heartbeat() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.heartbeats++
	return !f.failing
}

// Sent returns a snapshot of every message that has actually landed — never
// the ones rejected while failing was true. That distinction is the whole
// point of rule 5: alerted must mean delivered, not attempted.
func (f *fakeNotifier) Sent() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]string, len(f.sent))
	copy(out, f.sent)
	return out
}

func testConfig() Config {
	return Config{
		Token:          "test-token",
		DiscordWebhook: "http://example.invalid/hook",
		Subject:        "test-subject",
		PeriodSeconds:  10,
		GraceSeconds:   5,
		Bind:           "127.0.0.1",
		Port:           8080,
	}
}

// newTestWatcher builds a Watcher wired to a fakeNotifier and a clock the
// test controls directly — no sleeping through a real period. Advance time
// with *clock = clock.Add(d); the Watcher sees it on its next call, since
// w.now closes over the same variable clock points at.
//
// cfg.StateFile is pointed at a fresh t.TempDir() so persist() has somewhere
// real to write — leaving it empty makes every save fail and log noisily.
func newTestWatcher(t *testing.T, cfg Config, initial State) (w *Watcher, notifier *fakeNotifier, clock *time.Time) {
	t.Helper()
	cfg.StateFile = filepath.Join(t.TempDir(), "watcher-state.json")

	clockValue := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	notifier = &fakeNotifier{}
	w = NewWatcher(cfg, notifier, initial)
	w.now = func() time.Time { return clockValue }
	return w, notifier, &clockValue
}

// --- worked example -----------------------------------------------------

// TestRecordPingArmsADisarmedWatcher covers SPEC rule 1 and rule 3 together:
// a first-ever ping arms the watcher (sets LastSeen) but — since there was
// no outstanding alert to clear — needs no recovery notice.
func TestRecordPingArmsADisarmedWatcher(t *testing.T) {
	cfg := testConfig()
	w, notifier, clock := newTestWatcher(t, cfg, State{}) // never pinged
	*clock = clock.Add(time.Minute)

	recoveredAt, needsRecovery := w.RecordPing()

	if needsRecovery {
		t.Errorf("RecordPing() needsRecovery = true, want false — nothing was outstanding to recover from")
	}
	if !recoveredAt.IsZero() && needsRecovery {
		t.Errorf("recoveredAt = %v with needsRecovery = false; caller must not act on it in this case", recoveredAt)
	}

	got := w.State()
	if got.LastSeen == nil {
		t.Fatal("State().LastSeen = nil, want it set to the ping time")
	}
	if !got.LastSeen.Equal(*clock) {
		t.Errorf("State().LastSeen = %v, want %v", got.LastSeen, *clock)
	}
	if got.Alerted {
		t.Error("State().Alerted = true, want false after any accepted ping")
	}
	if sent := notifier.Sent(); len(sent) != 0 {
		t.Errorf("notifier.Sent() = %v, want none — a first ping never recovers anything", sent)
	}
}

// --- yours to fill in ----------------------------------------------------

// TestOnTickPagesAfterDeadline covers SPEC rule 2: an armed watcher that has
// gone silent past period+grace pages exactly once, and marks Alerted.
func TestOnTickPagesAfterDeadline(t *testing.T) {
	cfg := testConfig() // deadline = PeriodSeconds + GraceSeconds = 15s
	lastSeen := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	w, notifier, clock := newTestWatcher(t, cfg, State{LastSeen: &lastSeen})

	// Push the clock past the deadline before ticking.
	*clock = clock.Add(20 * time.Second)
	w.OnTick()
	_ = notifier // remove once you're using it in a real assertion below

	// assert notifier.Sent() has exactly one message, and that it
	if len(notifier.Sent()) != 1 {
		t.Fatalf("notifier.Sent() = %v, want exactly one message", notifier.Sent())
	}
	// starts with the down-page emoji (see DownMessage in notify.go).
	if !strings.HasPrefix(notifier.Sent()[0], "🚨") {
		t.Fatalf("notifier.Sent()[0] = %q, want it to start with the down-page emoji", notifier.Sent()[0])
	}
}

// TestOnTickStaysSilentWhenNeverPinged covers SPEC rule 3: a watcher that
// has never been pinged is disarmed and must never page, no matter how long
// it's been running.
func TestOnTickStaysSilentWhenNeverPinged(t *testing.T) {
	cfg := testConfig()
	w, notifier, clock := newTestWatcher(t, cfg, State{}) // fresh install

	*clock = clock.Add(24 * time.Hour) // well past any deadline
	w.OnTick()
	w.OnTick()
	_ = notifier // remove once you're using it in a real assertion below

	// assert notifier.Sent() is empty after both ticks.
	if len(notifier.Sent()) != 0 {
		t.Fatalf("notifier.Sent() = %v, want empty", notifier.Sent())
	}
	// assert w.State().Alerted is still false.
	if w.State().Alerted {
		t.Fatalf("w.State().Alerted = %v, want false", w.State().Alerted)
	}
}

// TestFailedDownPageIsRetriedNextTick covers SPEC rule 5's down-page path: a
// notification that fails to send leaves Alerted false, and a later tick
// (once the notifier recovers) tries again and succeeds.
func TestFailedDownPageIsRetriedNextTick(t *testing.T) {
	cfg := testConfig()
	lastSeen := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	w, notifier, clock := newTestWatcher(t, cfg, State{LastSeen: &lastSeen})
	*clock = clock.Add(20 * time.Second) // already past the deadline

	notifier.failing = true
	w.OnTick() // the send should fail

	// The mark was rolled back: Alerted means "someone was actually paged",
	// not "we tried". Leaving it true here would silence every later tick and
	// the outage would go unreported forever.
	if w.State().Alerted {
		t.Error("State().Alerted = true after a failed send, want false — nobody was paged")
	}
	if sent := notifier.Sent(); len(sent) != 0 {
		t.Errorf("notifier.Sent() = %v, want none — the send was attempted, not delivered", sent)
	}

	// No clock advance needed: a failed tick never touches LastSeen, so it is
	// still 20s against a 15s deadline. What arms this retry is the rollback
	// above, not the passage of more time.
	notifier.failing = false
	w.OnTick() // same outage, should retry and land this time

	sent := notifier.Sent()
	if len(sent) != 1 {
		t.Fatalf("notifier.Sent() = %v, want exactly one message once the notifier recovers", sent)
	}
	if !strings.HasPrefix(sent[0], "🚨") {
		t.Errorf("notifier.Sent()[0] = %q, want it to start with the down-page emoji", sent[0])
	}
	if !w.State().Alerted {
		t.Error("State().Alerted = false after a delivered page, want true — the outage is reported now")
	}
}
