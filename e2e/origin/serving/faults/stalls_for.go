package faults

import (
	"fmt"
	"net/http"
	"sync"
	"time"

	"go.yaml.in/yaml/v3"

	"github.com/stupside/castor/e2e/origin"
	"github.com/stupside/castor/e2e/origin/serving"
)

// StallsFor builds a behaviour that holds the nth segment for a while from its first request, then serves it, as in `stalls-for: {at: 40, seconds: 45}`.
type StallsFor struct{}

func (StallsFor) Name() string { return "stalls-for" }

func (StallsFor) Build(settings yaml.Node) (origin.Behaviour, error) {
	var in struct {
		At      int `yaml:"at"`
		Seconds int `yaml:"seconds"`
	}
	if err := settings.Decode(&in); err != nil || in.At < 1 || in.Seconds < 1 {
		return nil, fmt.Errorf("stalls-for: want the 1-based index of the segment that stalls and for how many seconds (%v)", err)
	}
	return &outage{at: in.At, lasts: time.Duration(in.Seconds) * time.Second}, nil
}

type outage struct {
	at    int
	lasts time.Duration
	seen  serving.Arrivals

	mu sync.Mutex
	// until is when the stalled segment is served again, set on its first request; a retry waits out the rest.
	until time.Time
}

func (*outage) Name() string { return "stalls-for" }

func (o *outage) Wrap(next http.Handler, p origin.Published) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !p.IsSegment(r) {
			next.ServeHTTP(w, r)
			return
		}
		if n, _ := o.seen.Arrive(r.URL.Path); n != o.at {
			next.ServeHTTP(w, r)
			return
		}
		o.mu.Lock()
		if o.until.IsZero() {
			o.until = time.Now().Add(o.lasts)
		}
		until := o.until
		o.mu.Unlock()
		select {
		case <-time.After(time.Until(until)):
			next.ServeHTTP(w, r)
		case <-r.Context().Done():
		case <-p.Closing:
		}
	})
}
