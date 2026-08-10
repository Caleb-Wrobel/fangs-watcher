package watcher

import "sync"

// Watcher owns the state and the two rules that change it (SPEC.md § The
// rules). mu guards state only — never a call into notifier, so a wedged
// webhook can never block an incoming ping.
type Watcher struct {
	config   Config
	notifier Notifier
	mu       sync.Mutex
	state    State
}

// NewWatcher wires a Config and Notifier (both already constructed elsewhere,
// e.g. in cmd/watcher/main.go) together with whatever state survived the last
// run (from LoadState, or a fresh State{} if there wasn't one).
func NewWatcher(config Config, notifier Notifier, initial State) *Watcher {
	return &Watcher{
		config:   config,
		notifier: notifier,
		state:    initial,
	}
}
