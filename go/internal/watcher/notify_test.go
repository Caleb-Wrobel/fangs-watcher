package watcher

import "testing"

func TestHumanDuration(t *testing.T) {
	// The table: one row per case, name -> input -> expected output. This is
	// the idiom you'll see all over Go's own stdlib tests (grep "tests := []struct").
	tests := []struct {
		name    string
		seconds float64
		want    string
	}{
		{"zero", 0, "0s"},
		{"under a minute", 45, "45s"},
		{"just under a minute", 59, "59s"},
		{"exactly a minute", 60, "1m"},
		{"minutes", 18 * 60, "18m"},
		{"just under an hour", 3599, "59m"},
		{"exactly an hour", 3600, "1h 00m"},
		{"hours and minutes", 2*3600 + 5*60, "2h 05m"},
		{"truncates, does not round", 119, "1m"}, // 1m59s -> "1m", not "2m"
		{"negative clocks to zero", -5, "0s"},
	}

	for _, tt := range tests {
		// t.Run gives each case its own named subtest — a failure reports
		// "TestHumanDuration/exactly_an_hour", not just a line number, and you
		// can run one case alone with `go test -run TestHumanDuration/hours`.
		t.Run(tt.name, func(t *testing.T) {
			got := HumanDuration(tt.seconds)
			if got != tt.want {
				t.Errorf("HumanDuration(%v) = %q, want %q", tt.seconds, got, tt.want)
			}
		})
	}
}
