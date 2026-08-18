package watcher

import (
	"log"
	"sync"
	"time"
)

// Watcher owns the state and the two rules that change it (SPEC.md § The
// rules), and nothing about HTTP — this is the file to compare against
// python/watcher/core.py and scala/src/main/scala/watcher/Watcher.scala.
//
// Concurrency: net/http serves each request on its own goroutine while the
// ticker goroutine (started in cmd/watcher/main.go) ticks on its own, so
// RecordPing and OnTick genuinely race. mu is held only across in-memory
// mutation and the statefile write, never across a call into notifier — a
// wedged webhook must not block a ping.
type Watcher struct {
	config   Config
	notifier Notifier

	mu    sync.Mutex
	state State

	// now is injected so tests can drive time by hand rather than sleep
	// through a real period; defaults to the real clock.
	now func() time.Time
}

// NewWatcher wires a Config and Notifier (both already constructed elsewhere,
// e.g. in cmd/watcher/main.go) together with whatever state survived the last
// run (from LoadState, or a fresh State{} if there wasn't one).
func NewWatcher(config Config, notifier Notifier, initial State) *Watcher {
	return &Watcher{
		config:   config,
		notifier: notifier,
		state:    initial,
		now:      time.Now,
	}
}

// State returns a snapshot of the current state, for tests and the ping
// handler's response.
func (w *Watcher) State() State {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.state
}

// RecordPing is SPEC rule 1: an accepted ping refreshes LastSeen and clears
// any outstanding alert.
//
// Returns the timestamp to announce a recovery for, and whether one is
// needed. The announcement is left to the caller — recordPing must return
// fast, so the ping's 200 is never held up waiting on a webhook.
func (w *Watcher) RecordPing() (recoveredAt time.Time, needsRecovery bool) {
	w.mu.Lock()
	defer w.mu.Unlock()

	now := w.now()
	wasAlerted := w.state.Alerted
	// Both fields move together, under the lock: a reader must never see the
	// new LastSeen paired with the old Alerted, a state that never was.
	w.persist(State{LastSeen: &now, Alerted: false})

	return now, wasAlerted
}

// AnnounceRecovery sends the recovery notice, after the ping has already
// been answered. On failure the alert goes back to outstanding so the next
// ping retries it — unless the state has moved on meanwhile (SPEC rule 5).
func (w *Watcher) AnnounceRecovery(at time.Time) {
	// Outside the lock: the send may block, and a ping arriving meanwhile
	// must not wait on it.
	if w.notifier.Notify(RecoveryMessage(w.config.Subject, at)) {
		return
	}

	w.mu.Lock()
	defer w.mu.Unlock()
	// Only re-arm if this is still the ping we were recovering for. A newer
	// ping owns the state now, and its own recovery (or none) applies.
	if w.state.LastSeen != nil && w.state.LastSeen.Equal(at) {
		w.persist(State{LastSeen: w.state.LastSeen, Alerted: true})
		log.Print("recovery failed to send; will retry on the next ping")
	}
}

// OnTick is one scheduler tick: heartbeat, then SPEC rule 2.
//
// Runs every period. Must never panic — a tick that dies takes the ticker
// goroutine, and so the whole dead-man's switch, with it.
func (w *Watcher) OnTick() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("tick failed; continuing: %v", r)
		}
	}()

	// The heartbeat fires every tick, before the rule — so the managed floor
	// hears from a watcher that is up but has nothing to page about.
	w.notifier.Heartbeat()
	w.evaluate()
}

// evaluate is SPEC rule 2, and the retry half of rule 5.
//
// Marks Alerted before sending, so a slow webhook cannot let the next tick
// page twice; rolls the mark back if the send does not land — but only if
// nothing has happened since. A ping that arrived mid-send has already
// cleared the alert, and re-arming here would page for an outage that is
// over.
func (w *Watcher) evaluate() {
	message, seenBefore, ok := w.markDownIfDark()
	if !ok {
		return
	}

	if w.notifier.Notify(message) {
		return
	}

	w.mu.Lock()
	defer w.mu.Unlock()
	// Retract only if nothing has moved since we marked: still alerted, and
	// the same LastSeen. A ping mid-send has already cleared the alert, and
	// re-arming would page for an outage that is over.
	if w.state.Alerted && w.state.LastSeen != nil && w.state.LastSeen.Equal(seenBefore) {
		w.persist(State{LastSeen: w.state.LastSeen, Alerted: false})
		log.Print("down page failed to send; will retry next tick")
	}
}

// markDownIfDark decides and marks under the lock; the send itself happens
// outside it, back in evaluate.
func (w *Watcher) markDownIfDark() (message string, seenBefore time.Time, ok bool) {
	w.mu.Lock()
	defer w.mu.Unlock()

	// Rule 3: never pinged is disarmed. Rule 2: one page per outage.
	if w.state.LastSeen == nil || w.state.Alerted {
		return "", time.Time{}, false
	}

	seen := *w.state.LastSeen
	elapsed := w.now().Sub(seen).Seconds()
	if elapsed <= float64(w.config.DeadlineSeconds()) {
		return "", time.Time{}, false
	}

	// Marked before sending; reverted in evaluate if the send does not land.
	w.persist(State{LastSeen: w.state.LastSeen, Alerted: true})
	return DownMessage(w.config.Subject, elapsed, seen), seen, true
}

// persist swaps in a new state and saves it, in that order, under the lock
// the caller is already holding. "Every state change is persisted" is only
// true if no tick can slip between the mutation and the save.
//
// A save failure is logged, not returned or retried immediately: SPEC's own
// retry story (rule 5) is about notifications, not statefile I/O, and a
// disk that's failing to write will fail the same way again next tick.
func (w *Watcher) persist(next State) {
	w.state = next
	if err := SaveState(next, w.config.StateFile); err != nil {
		log.Printf("failed to save statefile at %s: %v", w.config.StateFile, err)
	}
}
