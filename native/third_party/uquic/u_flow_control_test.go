package quic

import (
	"context"
	"testing"

	"github.com/refraction-networking/uquic/internal/monotime"
	"github.com/refraction-networking/uquic/internal/protocol"
	"github.com/refraction-networking/uquic/internal/utils"
	"github.com/refraction-networking/uquic/internal/wire"
)

func TestUFlowControlUsesAdvertisedReceiveCredit(t *testing.T) {
	params := &wire.TransportParameters{
		InitialMaxStreamDataBidiLocal:  6 << 20,
		InitialMaxStreamDataBidiRemote: 1 << 20,
		InitialMaxStreamDataUni:        2 << 20,
		InitialMaxData:                 16 << 20,
	}
	newConn := func(params *wire.TransportParameters) *Conn {
		c := &Conn{config: populateConfig(nil), localParams: params, peerParams: &wire.TransportParameters{}, perspective: protocol.PerspectiveClient, ctx: context.Background(), logger: utils.DefaultLogger}
		c.preSetup()
		return c
	}
	for _, tc := range []struct {
		name  string
		id    protocol.StreamID
		limit protocol.ByteCount
	}{
		{"local bidi", 0, params.InitialMaxStreamDataBidiLocal},
		{"peer bidi", 1, params.InitialMaxStreamDataBidiRemote},
		{"peer uni", 3, params.InitialMaxStreamDataUni},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := newConn(params)
			f := c.newFlowController(tc.id)
			if err := f.UpdateHighestReceived(tc.limit, false, monotime.Now()); err != nil {
				t.Fatalf("rejected advertised credit: %v", err)
			}
			if err := f.UpdateHighestReceived(tc.limit+1, false, monotime.Now()); err == nil {
				t.Fatal("accepted data beyond advertised credit")
			}
		})
	}
	t.Run("connection", func(t *testing.T) {
		c := newConn(params)
		controller := c.connFlowController.(interface {
			IncrementHighestReceived(protocol.ByteCount, monotime.Time) error
		})
		if err := controller.IncrementHighestReceived(params.InitialMaxData, monotime.Now()); err != nil {
			t.Fatalf("rejected advertised connection credit: %v", err)
		}
		if err := controller.IncrementHighestReceived(1, monotime.Now()); err == nil {
			t.Fatal("accepted data beyond connection credit")
		}
	})
	t.Run("ordinary connection", func(t *testing.T) {
		c := newConn(nil)
		f := c.newFlowController(0)
		limit := protocol.ByteCount(c.config.InitialStreamReceiveWindow)
		if err := f.UpdateHighestReceived(limit, false, monotime.Now()); err != nil {
			t.Fatal(err)
		}
		if err := f.UpdateHighestReceived(limit+1, false, monotime.Now()); err == nil {
			t.Fatal("ordinary connection lost configured limit")
		}
	})
}
