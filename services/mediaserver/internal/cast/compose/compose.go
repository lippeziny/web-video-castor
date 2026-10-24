// Package compose decides the shape of a cast: handed to a device that fetches for itself, remuxed on the fly, or read once and served.
package compose

// Kind is what castor does with the bytes of one cast.
type Kind string

const (
	// Handoff: the device fetches the source itself.
	Handoff Kind = "handoff"
	// Remux: castor reads the source and serves what it produced.
	Remux Kind = "remux"
	// ReadOnce: castor buffers the source locally and serves what it produced from the buffer.
	ReadOnce Kind = "read-once"
)

// For is the kind of cast shape s calls for; a remux answers a device that rejects the source or whose headers do not match.
func For(s Shape) Kind {
	switch {
	case !s.Device.SelfFetch:
		return ReadOnce
	case s.handoff():
		return Handoff
	default:
		return Remux
	}
}
