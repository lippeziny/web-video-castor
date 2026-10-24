package ffmpeg

import (
	"context"
	"os/exec"
	"testing"
)

func TestACancelledTestEncodeIsNotRemembered(t *testing.T) {
	bin, err := exec.LookPath("true")
	if err != nil {
		t.Skip("no true binary")
	}
	proven := &provenEncoders{ffmpegPath: bin, slots: map[string]*proof{}}
	cancelled, cancel := context.WithCancel(t.Context())
	cancel()
	if works, _ := proven.available(cancelled, libx264); works {
		t.Fatal("a cancelled test encode reported the encoder available")
	}
	works, ran := proven.available(t.Context(), libx264)
	if !works {
		t.Error("a cancelled test encode was cached as unavailable")
	}
	if !ran {
		t.Error("the first uncancelled ask did not run the test encode")
	}
	if _, ran := proven.available(t.Context(), libx264); ran {
		t.Error("a proven encoder was tested again")
	}
}
