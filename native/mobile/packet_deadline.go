package mobile

import (
	"sync"
	"time"
)

// A deadline channel is shared by already-blocked and future packet operations.
// Resetting a timer cannot allow its stale callback to expire a newer deadline.
type packetDeadline struct {
	mu         sync.Mutex
	timer      *time.Timer
	generation uint64
	expired    bool
	closed     bool
	signal     chan struct{}
}

func (d *packetDeadline) wait() <-chan struct{} {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.signal == nil {
		d.signal = make(chan struct{})
	}
	return d.signal
}

func (d *packetDeadline) set(t time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return
	}
	d.generation++
	generation := d.generation
	if d.timer != nil {
		d.timer.Stop()
		d.timer = nil
	}
	if d.signal == nil || d.expired {
		d.signal = make(chan struct{})
	}
	d.expired = false
	if t.IsZero() {
		return
	}
	if !t.After(time.Now()) {
		d.expired = true
		close(d.signal)
		return
	}
	d.timer = time.AfterFunc(time.Until(t), func() {
		d.mu.Lock()
		defer d.mu.Unlock()
		if d.generation != generation || d.expired {
			return
		}
		d.expired = true
		close(d.signal)
	})
}

func (d *packetDeadline) stop() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return
	}
	d.closed = true
	d.generation++
	if d.timer != nil {
		d.timer.Stop()
		d.timer = nil
	}
	if d.signal == nil {
		d.signal = make(chan struct{})
	}
	if !d.expired {
		close(d.signal)
		d.expired = true
	}
}
