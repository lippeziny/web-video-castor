package chromecast

import (
	"context"
	"net"
	"testing"
	"testing/synctest"

	castmedia "github.com/vishen/go-chromecast/cast"
)

func TestAReceiverThatAnswersNoPingIsDroppedAfterThreeMissedBeats(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		near, far := net.Pipe()
		sender := newChannel(near, func([]byte) {})
		// Reads every frame and answers none.
		silent := &channel{conn: far, gone: make(chan struct{})}
		go func() {
			for {
				if _, err := silent.receive(); err != nil {
					return
				}
			}
		}()

		synctest.Sleep((heartbeatMisses - 1) * heartbeatEvery)
		select {
		case <-sender.gone:
			t.Fatal("the connection was dropped before the receiver missed its beats")
		default:
		}
		synctest.Sleep(2 * heartbeatEvery)
		select {
		case <-sender.gone:
		default:
			t.Fatal("a receiver that answered no PING kept its connection")
		}
		_ = far.Close()
	})
}

func TestAReceiverThatAnswersEachPingKeepsItsConnection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		near, far := net.Pipe()
		sender := newChannel(near, func([]byte) {})
		peer := &channel{conn: far, writing: make(chan struct{}, 1)}
		go func() {
			for {
				if _, err := peer.receive(); err != nil {
					return
				}
				go func() {
					_ = peer.send(context.Background(), senderID, nsHeartbeat, &castmedia.PayloadHeader{Type: msgPong})
				}()
			}
		}()

		synctest.Sleep(10 * heartbeatMisses * heartbeatEvery)
		select {
		case <-sender.gone:
			t.Fatal("a receiver answering its pings was dropped")
		default:
		}
		_ = sender.Close()
		_ = far.Close()
	})
}
