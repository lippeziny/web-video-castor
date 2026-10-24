package receiver

import (
	"bytes"
	"io"
	"testing"
	"time"
)

// pesPacket is one transport packet opening a PES with the given presentation time.
func pesPacket(pts time.Duration) []byte {
	pkt := make([]byte, tsPacket)
	for i := range pkt {
		pkt[i] = 0xff
	}
	pkt[0], pkt[1], pkt[2], pkt[3] = tsSync, 0x40|0x01, 0x00, 0x10
	ticks := int64(pts * 90000 / time.Second)
	copy(pkt[4:], []byte{0, 0, 1, 0xe0, 0, 0, 0x80, 0x80, 5,
		byte(0x21 | (ticks>>29)&0x0e), byte(ticks >> 22), byte(0x01 | (ticks>>14)&0xfe), byte(ticks >> 7), byte(0x01 | (ticks<<1)&0xfe)})
	return pkt
}

func TestTheClockFollowsThePresentationTimesItReads(t *testing.T) {
	var c clock
	stream := bytes.Join([][]byte{pesPacket(10 * time.Second), make([]byte, tsPacket), pesPacket(12 * time.Second)}, nil)
	// Padding without the sync byte is skipped to the next packet.
	stream[tsPacket] = 0
	if _, err := io.Copy(io.Discard, &timed{r: bytes.NewReader(stream), clock: &c}); err != nil {
		t.Fatal(err)
	}
	left, ok := c.buffered(time.Now())
	if !ok || left < 1900*time.Millisecond || left > 2*time.Second {
		t.Errorf("buffered = %v, %t; want about 2s of media between the first and last timestamps", left, ok)
	}
	c.hold(true)
	time.Sleep(50 * time.Millisecond)
	if held, _ := c.buffered(time.Now()); held < left-5*time.Millisecond {
		t.Errorf("buffered fell to %v while held, want the clock stopped", held)
	}
}
