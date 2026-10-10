package quic

import (
	"testing"

	"github.com/refraction-networking/uquic/internal/handshake"
	"github.com/refraction-networking/uquic/internal/monotime"
	"github.com/refraction-networking/uquic/internal/protocol"
	"github.com/refraction-networking/uquic/internal/wire"
)

// No streams, ACKs or retransmissions remain: PTO must still send an ack-eliciting PING.
func TestUPackerEmpty1RTTProbe(t *testing.T) {
	p := newUPacketPacker(&packetPacker{
		cryptoSetup: probeKeys{}, pnManager: probeNumbers{}, framer: probeFrames{},
		acks: probeAcks{}, retransmissionQueue: newRetransmissionQueue(),
		getDestConnID: func() protocol.ConnectionID { return protocol.ConnectionID{} },
	}, &QUICSpec{})
	packet, err := p.PackPTOProbePacket(protocol.Encryption1RTT, 1200, false, monotime.Now(), protocol.Version1)
	if err != nil || packet != nil {
		t.Fatalf("optional probe: packet=%v err=%v", packet, err)
	}
	packet, err = p.PackPTOProbePacket(protocol.Encryption1RTT, 1200, true, monotime.Now(), protocol.Version1)
	if err != nil || packet == nil || packet.shortHdrPacket == nil {
		t.Fatalf("mandatory probe: packet=%v err=%v", packet, err)
	}
	defer packet.buffer.Release()
	frames := packet.shortHdrPacket.Frames
	if len(frames) != 1 {
		t.Fatalf("probe frames: %v", frames)
	}
	if _, ok := frames[0].Frame.(*wire.PingFrame); !ok {
		t.Fatalf("expected PING, got %T", frames[0].Frame)
	}
}

type probeKeys struct{ sealingManager }

func (probeKeys) Get1RTTSealer() (handshake.ShortHeaderSealer, error) { return probeSealer{}, nil }

type probeSealer struct{}

func (probeSealer) Overhead() int                  { return 16 }
func (probeSealer) KeyPhase() protocol.KeyPhaseBit { return protocol.KeyPhaseZero }
func (probeSealer) Seal(dst, src []byte, _ protocol.PacketNumber, _ []byte) []byte {
	return append(append(dst, src...), make([]byte, 16)...)
}
func (probeSealer) EncryptHeader([]byte, *byte, []byte) {}

type probeNumbers struct{}

func (probeNumbers) PeekPacketNumber(protocol.EncryptionLevel) (protocol.PacketNumber, protocol.PacketNumberLen) {
	return 7, protocol.PacketNumberLen2
}
func (probeNumbers) PopPacketNumber(protocol.EncryptionLevel) protocol.PacketNumber { return 7 }

type probeFrames struct{ frameSource }

func (probeFrames) HasData() bool { return false }

type probeAcks struct{}

func (probeAcks) GetAckFrame(protocol.EncryptionLevel, monotime.Time, bool) *wire.AckFrame {
	return nil
}
