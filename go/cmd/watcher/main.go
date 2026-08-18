// Command watcher is the entrypoint: config, the ticker, then the server.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/caleb-wrobel/fangs-watcher/go/internal/watcher"
)

func main() {
	os.Exit(run())
}

func run() int {
	cfg, problems := watcher.ConfigFromEnv()
	if len(problems) > 0 {
		// Fail loudly at boot rather than midway through an incident. The
		// errors name the offending variables but never their values, which
		// may be secrets.
		fmt.Fprintln(os.Stderr, "invalid configuration (see SPEC.md § Configuration):")
		for _, p := range problems {
			fmt.Fprintf(os.Stderr, "  %s\n", p)
		}
		return 2
	}

	notifier := watcher.NewHTTPNotifier(cfg.DiscordWebhook, cfg.HealthcheckURL)
	// Rule 4: load whatever survived the last run, so a restart mid-outage
	// still pages on its first tick. An absent or corrupt file loads fresh.
	state := watcher.LoadState(cfg.StateFile)
	w := watcher.NewWatcher(cfg, notifier, state)

	log.Printf(
		"watching %s — listening on %s:%d, state at %s (dark after %ds of silence)",
		cfg.Subject, cfg.Bind, cfg.Port, cfg.StateFile, cfg.DeadlineSeconds(),
	)

	stopTicker := startTicker(cfg, w)
	defer stopTicker()

	server := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Bind, cfg.Port),
		Handler: watcher.NewHandler(w),
	}
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Fprintf(os.Stderr, "server failed: %v\n", err)
		return 1
	}
	return 0
}

// startTicker ticks every period, forever, on its own goroutine, until the
// returned func is called.
//
// OnTick swallows its own failures, but a belt-and-suspenders recover here
// too: a goroutine that panics takes the whole process down, which would
// silence the ticker for good rather than merely skip one tick.
func startTicker(cfg watcher.Config, w *watcher.Watcher) (stop func()) {
	done := make(chan struct{})
	period := time.Duration(cfg.PeriodSeconds) * time.Second
	go func() {
		// Fixed *delay*, not fixed *rate*: the next wait starts only once a
		// tick has finished, so a slow webhook that blocks a tick cannot leave
		// time.Ticker holding a backlogged tick to deliver the instant it
		// returns — each of which could send. time.Ticker is fixed-rate and
		// buffers one missed tick, which is exactly that burst. This matches
		// Python's sleep(period) loop and Scala's scheduleWithFixedDelay.
		//
		// The mild cadence stretch during a slow send is harmless: the rule
		// counts wall-clock since LastSeen, so a late tick is still correct.
		timer := time.NewTimer(period)
		defer timer.Stop()
		for {
			select {
			case <-timer.C:
				w.OnTick()
				timer.Reset(period)
			case <-done:
				return
			}
		}
	}()
	return func() { close(done) }
}
