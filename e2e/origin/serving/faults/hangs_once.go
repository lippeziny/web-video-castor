package faults

import (
	"fmt"
	"net/http"

	"go.yaml.in/yaml/v3"

	"github.com/stupside/castor/e2e/origin"
	"github.com/stupside/castor/e2e/origin/serving"
)

// HangsOnce builds a behaviour that never answers the first request for the nth segment, and answers every retry of it, as in `hangs-once: 12`.
type HangsOnce struct{}

func (HangsOnce) Name() string { return "hangs-once" }

func (HangsOnce) Build(settings yaml.Node) (origin.Behaviour, error) {
	var n int
	if err := settings.Decode(&n); err != nil || n < 1 {
		return nil, fmt.Errorf("hangs-once: want the 1-based index of the segment whose first request hangs (%v)", err)
	}
	return &hang{at: n}, nil
}

type hang struct {
	at   int
	seen serving.Arrivals
}

func (*hang) Name() string { return "hangs-once" }

func (h *hang) Wrap(next http.Handler, p origin.Published) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !p.IsSegment(r) {
			next.ServeHTTP(w, r)
			return
		}
		if n, first := h.seen.Arrive(r.URL.Path); n == h.at && first {
			p.Held(r)
			return
		}
		next.ServeHTTP(w, r)
	})
}
