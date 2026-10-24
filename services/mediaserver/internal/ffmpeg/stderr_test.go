package ffmpeg

import (
	"slices"
	"strings"
	"testing"
)

// A blocking write into a full stderr pipe stops the encode dead, so an over-long line must not stop the drain.
func TestTheDrainOutlastsALineTooLongToHold(t *testing.T) {
	marker := "[mp4 @ 0x1] [warning] Malformed AAC bitstream detected"
	tail, markers := drainLines(t, strings.Repeat("x", stderrLineBuffer*3), marker)
	if markers.failure() == nil || !slices.Contains(tail.snapshot(), marker) {
		t.Error("nothing printed after the over-long line was read")
	}
}

func drainLines(t *testing.T, lines ...string) (*ringTail, *markerWatch) {
	t.Helper()
	tail, markers := newRingTail(), &markerWatch{}
	drainStderr(t.Context(), strings.NewReader(strings.Join(lines, "\n")+"\n"), tail, markers)
	return tail, markers
}

// A complaint keeps its place whether or not ffmpeg named a context before the level tag.
func TestOnlyComplaintsAreRetained(t *testing.T) {
	tail, _ := drainLines(t, "[info] banner", "[hls @ 0x1] [verbose] opening seg", "[in#0 @ 0x1] [error] Error opening input", "[fatal] exiting", "[warning] slow")
	want := []string{"[in#0 @ 0x1] [error] Error opening input", "[fatal] exiting", "[warning] slow"}
	if got := tail.snapshot(); !slices.Equal(got, want) {
		t.Errorf("retained %q, want %q", got, want)
	}
}
