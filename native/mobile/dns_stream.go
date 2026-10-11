package mobile

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

// TCP DNS shares UDP's DoH fallback and IPv6 policy. Independent pipe directions
// preserve responses after a client's FIN; the bounded queue accepts pipelining.
func dialDNSStream(ctx context.Context, metadata *M.Metadata, dialUDP func(*M.Metadata) (net.PacketConn, error), own func(net.Conn) (net.Conn, error)) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	packet, err := dialUDP(metadata)
	if err != nil {
		return nil, err
	}
	requests, writer := net.Pipe()
	reader, responses := net.Pipe()
	stream := &dnsStreamConn{Conn: reader, writer: writer, requests: requests, responses: responses, packet: packet, closed: make(chan struct{})}
	conn, err := own(stream)
	if err != nil {
		return nil, err
	}
	queries := make(chan []byte, 8)
	go func() {
		defer close(queries)
		var header [2]byte
		for {
			if _, err := io.ReadFull(requests, header[:]); err != nil {
				if err != io.EOF {
					_ = conn.Close()
				}
				return
			}
			size := int(binary.BigEndian.Uint16(header[:]))
			if size < 12 {
				_ = conn.Close()
				return
			}
			query := make([]byte, size)
			if _, err := io.ReadFull(requests, query); err != nil {
				_ = conn.Close()
				return
			}
			select {
			case queries <- query:
			case <-stream.closed:
				return
			}
		}
	}()
	go func() {
		defer conn.Close()
		response := make([]byte, 65537)
		for {
			select {
			case query, ok := <-queries:
				if !ok {
					return
				}
				if _, err := packet.WriteTo(query, nil); err != nil {
					return
				}
				n, _, err := packet.ReadFrom(response[2:])
				if err != nil {
					return
				}
				binary.BigEndian.PutUint16(response[:2], uint16(n))
				if _, err := responses.Write(response[:n+2]); err != nil {
					return
				}
			case <-stream.closed:
				return
			}
		}
	}()
	return conn, nil
}

type dnsStreamConn struct {
	net.Conn
	writer, requests, responses net.Conn
	packet                      net.PacketConn
	closed                      chan struct{}
	once                        sync.Once
}

func (c *dnsStreamConn) Write(b []byte) (int, error)        { return c.writer.Write(b) }
func (c *dnsStreamConn) SetWriteDeadline(t time.Time) error { return c.writer.SetWriteDeadline(t) }
func (c *dnsStreamConn) SetDeadline(t time.Time) error {
	if err := c.SetReadDeadline(t); err != nil {
		return err
	}
	return c.SetWriteDeadline(t)
}
func (c *dnsStreamConn) CloseWrite() error { return c.writer.Close() }
func (c *dnsStreamConn) CloseRead() error  { return c.Conn.Close() }
func (c *dnsStreamConn) Close() error {
	c.once.Do(func() {
		close(c.closed)
		_ = c.packet.Close()
		_ = c.requests.Close()
		_ = c.responses.Close()
		_ = c.writer.Close()
		_ = c.Conn.Close()
	})
	return nil
}
