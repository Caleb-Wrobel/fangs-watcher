package watcher

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"time"
)

// State is the two persisted fields, and nothing else. LastSeen absent means
// never pinged — and so, disarmed (SPEC rule 3).
type State struct {
	LastSeen *time.Time
	Alerted  bool
}

// stateJSON is the wire shape: {"last_seen": <iso8601|null>, "alerted": <bool>}.
// Hand-written rather than derived from State directly so the field names
// stay the contract's snake_case regardless of Go's own conventions.
type stateJSON struct {
	LastSeen *string `json:"last_seen"`
	Alerted  bool    `json:"alerted"`
}

// toJSON renders the SPEC's shape, indented because you will read this by
// eye, at 3am, over SSH.
func (s State) toJSON() ([]byte, error) {
	doc := stateJSON{Alerted: s.Alerted}
	if s.LastSeen != nil {
		text := s.LastSeen.UTC().Format(time.RFC3339)
		doc.LastSeen = &text
	}
	return json.MarshalIndent(doc, "", "  ")
}

// LoadState reads state, treating an absent or unreadable file as fresh.
//
// A corrupt statefile is deliberately not fatal: refusing to boot would take
// the watcher down for good over a file it can simply rewrite. It re-arms on
// the next ping, which is the safe direction to fail — a watcher that is
// briefly disarmed is better than one that is not running.
func LoadState(path string) State {
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			log.Printf("no statefile at %s — starting fresh and disarmed", path)
		} else {
			log.Printf("unreadable statefile at %s (%v) — starting fresh and disarmed", path, err)
		}
		return State{}
	}

	var doc stateJSON
	if err := json.Unmarshal(raw, &doc); err != nil {
		log.Printf("unreadable statefile at %s (%v) — starting fresh and disarmed", path, err)
		return State{}
	}

	state := State{Alerted: doc.Alerted}
	if doc.LastSeen != nil {
		// Accept any RFC3339 instant, not only the Z form this impl writes —
		// the statefile format is the contract's, so another implementation's
		// file (e.g. Python's +00:00 offset form) must load here too.
		if t, err := time.Parse(time.RFC3339, *doc.LastSeen); err == nil {
			state.LastSeen = &t
		} else {
			log.Printf("unparseable last_seen in %s — treating as absent", path)
		}
	}
	return state
}

// SaveState writes atomically: temp file in the same dir → fsync → rename.
//
// Same directory matters — a rename is only atomic within a filesystem. The
// directory fsync is what actually makes the rename durable across a power
// loss; without it the rename can still be lost.
//
// The smoke test checks this by inode: a rename gives the path a new one,
// while an in-place rewrite keeps it (SPEC.md § The smoke test, check 7).
func SaveState(s State, path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}

	body, err := s.toJSON()
	if err != nil {
		return err
	}

	// Dotted prefix so a temp glimpsed mid-write does not look like the real
	// thing to anyone watching the directory.
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	// Never leave the temp beside the statefile: the SPEC promises the
	// directory is clean in normal operation, and a half-written sibling is
	// exactly the confusion an incident does not need.
	cleanup := func() { _ = os.Remove(tmpPath) }

	if _, err := tmp.Write(body); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	// The data must be on the platter before the rename publishes it;
	// otherwise a power loss can leave the new name over empty contents.
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return err
	}

	if err := os.Rename(tmpPath, path); err != nil {
		cleanup()
		return err
	}
	fsyncDir(dir)
	return nil
}

// fsyncDir fsyncs a directory, so the rename itself survives a power loss.
//
// Not portable to Windows, where a directory cannot be opened for fsync; the
// watcher targets Linux containers, and a missed fsync here is not worth
// failing an otherwise complete write over.
func fsyncDir(dir string) {
	f, err := os.Open(dir)
	if err != nil {
		return
	}
	defer f.Close()
	_ = f.Sync()
}
