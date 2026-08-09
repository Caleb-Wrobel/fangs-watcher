// Package watcher implements SPEC.md: the dead-man's-switch contract shared by
// every language in this repo.
package watcher

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config is the WATCHER_* environment, typed and validated once at startup —
// a bad WATCHER_PERIOD_SECONDS should be a refusal to boot, not a panic from
// the ticker goroutine at 3am. See SPEC.md § Configuration.
type Config struct {
	Token          string
	DiscordWebhook string
	Subject        string
	PeriodSeconds  int
	GraceSeconds   int
	StateFile      string
	// Bind defaults to loopback: in production this runs as one container in
	// a podman kube play pod, and the networking sidecar sharing the
	// namespace reaches it over 127.0.0.1. Binding all interfaces would
	// publish /ping on the pod IP, bypassing the intended front door.
	// See SPEC.md § Security.
	Bind           string
	Port           int
	HealthcheckURL string // empty means the feature is off
}

const (
	DefaultSubject       = "the subject"
	DefaultPeriodSeconds = 300
	DefaultGraceSeconds  = 900
	DefaultStateFile     = "./watcher-state.json"
	DefaultBind          = "127.0.0.1"
	DefaultPort          = 8080
)

// DeadlineSeconds is how long a silence may run before it counts as dark
// (SPEC rule 2).
func (c Config) DeadlineSeconds() int {
	return c.PeriodSeconds + c.GraceSeconds
}

// ConfigFromEnv reads the WATCHER_* environment. It reports every problem at
// once rather than the first, so a misconfigured deploy is fixed in one pass.
// Messages name the offending variable but never its value — two of them are
// secrets (SPEC.md § Configuration).
func ConfigFromEnv() (Config, []string) {
	var problems []string

	// A variable set to blank is a variable that is not set.
	raw := func(name string) (string, bool) {
		v := strings.TrimSpace(os.Getenv(name))
		if v == "" {
			return "", false
		}
		return v, true
	}

	required := func(name string) string {
		v, ok := raw(name)
		if !ok {
			problems = append(problems, name+" is required")
		}
		return v
	}

	intVar := func(name string, def int, valid func(int) bool, expectation string) int {
		v, ok := raw(name)
		if !ok {
			return def
		}
		n, err := strconv.Atoi(v)
		if err != nil || !valid(n) {
			problems = append(problems, fmt.Sprintf("%s %s", name, expectation))
			return def
		}
		return n
	}

	cfg := Config{
		Token:          required("WATCHER_TOKEN"),
		DiscordWebhook: required("WATCHER_DISCORD_WEBHOOK"),
		Subject:        DefaultSubject,
		PeriodSeconds: intVar("WATCHER_PERIOD_SECONDS", DefaultPeriodSeconds,
			func(n int) bool { return n > 0 }, "must be a positive integer"),
		GraceSeconds: intVar("WATCHER_GRACE_SECONDS", DefaultGraceSeconds,
			func(n int) bool { return n >= 0 }, "must be a non-negative integer"),
		StateFile: DefaultStateFile,
		Bind:      DefaultBind,
		Port: intVar("WATCHER_PORT", DefaultPort,
			func(n int) bool { return n >= 1 && n <= 65535 }, "must be an integer between 1 and 65535"),
	}

	if v, ok := raw("WATCHER_SUBJECT"); ok {
		cfg.Subject = v
	}
	if v, ok := raw("WATCHER_STATE_FILE"); ok {
		// A relative path resolves against the working directory, matching
		// the other impls.
		if abs, err := filepath.Abs(v); err == nil {
			cfg.StateFile = abs
		} else {
			problems = append(problems, "WATCHER_STATE_FILE is not a usable path")
		}
	} else if abs, err := filepath.Abs(cfg.StateFile); err == nil {
		cfg.StateFile = abs
	}
	if v, ok := raw("WATCHER_BIND"); ok {
		cfg.Bind = v
	}
	if v, ok := raw("WATCHER_HEALTHCHECK_URL"); ok {
		cfg.HealthcheckURL = v
	}

	return cfg, problems
}
