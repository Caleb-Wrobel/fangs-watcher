package watcher

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"
)

// Notifier talks to the outside world: the notification webhook and the
// healthchecks.io heartbeat.
//
// SPEC.md § Notifications: a notification is a POST of {"content": "<msg>"}
// in Discord's shape. A failed POST is logged and never crashes the watcher —
// the caller decides whether to retry, since only it knows if the page still
// applies.
//
// An interface, not a struct, so the rules in watcher.go can be tested
// against a recording double that can be told to start failing — the only
// way to reach SPEC rule 5.
type Notifier interface {
	// Notify POSTs a notification. Returns whether it landed.
	Notify(content string) bool
	// Heartbeat GETs the healthchecks.io check. An unset URL means the
	// feature is off, which is a success: nothing was meant to happen and
	// nothing failed.
	Heartbeat() bool
}

// HumanDuration renders a duration as an on-call human reads it: 45s, 18m,
// 2h 05m.
//
// Truncated rather than rounded — the reader wants a magnitude, not a
// measurement — and deliberately without a day unit: 26h 00m is quicker to
// reason about mid-incident than 1d 2h.
func HumanDuration(seconds float64) string {
	// A clock that steps backwards must not render a negative outage.
	total := int64(seconds)
	if total < 0 {
		total = 0
	}
	switch {
	case total < 60:
		return fmt.Sprintf("%ds", total)
	case total < 3600:
		return fmt.Sprintf("%dm", total/60)
	default:
		return fmt.Sprintf("%dh %02dm", total/3600, (total%3600)/60)
	}
}

// DownMessage is: 🚨 {subject} is dark — no check-in for {elapsed}. Last check-in: {last_seen}.
func DownMessage(subject string, elapsedSeconds float64, lastSeen time.Time) string {
	return fmt.Sprintf("🚨 %s is dark — no check-in for %s. Last check-in: %s.",
		subject, HumanDuration(elapsedSeconds), lastSeen.UTC().Format(time.RFC3339))
}

// RecoveryMessage is: ✅ {subject} is back — check-in resumed at {now}.
func RecoveryMessage(subject string, now time.Time) string {
	return fmt.Sprintf("✅ %s is back — check-in resumed at %s.", subject, now.UTC().Format(time.RFC3339))
}

// HTTPNotifier is the real Notifier, over blocking HTTP.
//
// Timeouts are load-bearing: a black-holed webhook must not wedge a ticker
// whose period may be only a few seconds.
type HTTPNotifier struct {
	WebhookURL     string
	HealthcheckURL string // empty disables the heartbeat
	client         *http.Client
}

// connectAndReadTimeout bounds an entire notification call: generous enough
// for a slow webhook, short enough that a black-holed endpoint cannot wedge a
// ticker whose period may be only a few seconds.
const connectAndReadTimeout = 10 * time.Second

func NewHTTPNotifier(webhookURL, healthcheckURL string) *HTTPNotifier {
	return &HTTPNotifier{
		WebhookURL:     webhookURL,
		HealthcheckURL: healthcheckURL,
		client:         &http.Client{Timeout: connectAndReadTimeout},
	}
}

// Notify POSTs the message. The URL is never logged: it embeds a credential
// (SPEC.md § Configuration).
func (n *HTTPNotifier) Notify(content string) bool {
	body, err := json.Marshal(map[string]string{"content": content})
	if err != nil {
		log.Printf("notification failed (encode: %v): %s", err, content)
		return false
	}

	resp, err := n.client.Post(n.WebhookURL, "application/json", bytes.NewReader(body))
	if err != nil {
		log.Printf("notification failed (%v): %s", err, content)
		return false
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		log.Printf("notified: %s", content)
		return true
	}
	log.Printf("notification failed (HTTP %d): %s", resp.StatusCode, content)
	return false
}

// Heartbeat GETs the healthchecks.io check. An unset URL means the feature is
// off, which is a success.
func (n *HTTPNotifier) Heartbeat() bool {
	if n.HealthcheckURL == "" {
		return true
	}
	resp, err := n.client.Get(n.HealthcheckURL)
	if err != nil {
		log.Printf("self-heartbeat failed (%v)", err)
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return true
	}
	log.Printf("self-heartbeat failed (HTTP %d)", resp.StatusCode)
	return false
}
