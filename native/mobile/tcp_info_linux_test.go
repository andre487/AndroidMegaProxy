package mobile

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func TestTCPInfoRealSocket(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		c, err := listener.Accept()
		if err == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	stats := resetStats()
	c, err := dialMeasuredTCP(context.Background(), &net.Dialer{Timeout: time.Second}, listener.Addr().String(), stats)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 4)
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "ping" {
		t.Fatalf("echo %q: %v", b, err)
	}
	got := stats.snapshot(time.Now())
	if got.TCPRTTMillis == nil || *got.TCPRTTMillis <= 0 || got.TCPRetransmits == nil {
		t.Fatalf("TCP_INFO unavailable: %+v", got)
	}
	c.Close()
	<-done
	if len(stats.sockets) != 0 {
		t.Fatal("socket not unregistered")
	}
}
