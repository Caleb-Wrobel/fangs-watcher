package watcher

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// NewHandler builds the HTTP surface: exactly POST /ping/{token} and
// GET /healthz (SPEC.md § HTTP surface). No other routes.
//
// Built on the bare http.ServeMux rather than Go 1.22's method-matching
// patterns ("POST /ping/{token}") deliberately: that newer form answers a
// path match with the wrong method using 405 Method Not Allowed, which
// SPEC.md explicitly forbids — a 405 confirms /ping/{token} exists whatever
// token was tried, defeating the 404-on-mismatch rule just as thoroughly as
// a 401 would. So every route below checks its own method by hand and falls
// through to notFound, and the mux itself only ever dispatches by path.
func NewHandler(w *Watcher) http.Handler {
	expectedToken := []byte(w.config.Token)

	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(rw http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			notFound(rw)
			return
		}
		rw.WriteHeader(http.StatusOK)
		rw.Write([]byte("ok"))
	})

	mux.HandleFunc("/ping/", func(rw http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			notFound(rw)
			return
		}

		token := strings.TrimPrefix(r.URL.Path, "/ping/")
		// Constant-time: the token is a bearer credential, and a timing
		// oracle would let it be guessed a character at a time.
		if subtle.ConstantTimeCompare([]byte(token), expectedToken) != 1 {
			notFound(rw)
			return
		}

		// The check-in is durable before the 200 is written (RecordPing
		// persists), but the recovery notice must not delay it, so it rides
		// its own goroutine.
		recoveredAt, needsRecovery := w.RecordPing()
		if needsRecovery {
			go w.AnnounceRecovery(recoveredAt)
		}

		rw.WriteHeader(http.StatusOK)
		rw.Write([]byte("ok"))
	})

	return mux
}

// notFound answers byte-identical to every other 404, so a wrong token and
// an unknown path are indistinguishable — no wording an attacker could
// measure (SPEC.md § Security).
func notFound(rw http.ResponseWriter) {
	rw.WriteHeader(http.StatusNotFound)
	rw.Write([]byte("not found"))
}
