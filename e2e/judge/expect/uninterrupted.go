package expect

import (
	"fmt"
	"time"

	"github.com/stupside/castor/e2e/judge"
)

// Uninterrupted holds that castor kept ahead of playback without ever pausing the receiver.
type Uninterrupted struct{}

func (Uninterrupted) Name() string { return "uninterrupted" }

func (Uninterrupted) Judge(e judge.Evidence) []string {
	return judge.FailIf(e.Received.Holds > 0,
		fmt.Sprintf("castor paused the receiver %d times for %v, want it never paused", e.Received.Holds, e.Received.Held.Round(time.Second)))
}
