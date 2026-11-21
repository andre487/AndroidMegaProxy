package main

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestPercentiles(t *testing.T) {
	samples := []time.Duration{100 * time.Millisecond, time.Millisecond, 3 * time.Millisecond, 2 * time.Millisecond}
	if got := percentiles(samples); got != [3]float64{2, 100, 100} {
		t.Fatalf("unexpected nearest-rank percentiles: %v", got)
	}
	if got := percentiles(nil); got != [3]float64{} {
		t.Fatalf("unexpected empty result: %v", got)
	}
}

func TestProbeLoopback(t *testing.T) {
	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	go func() {
		for {
			conn, err := tcp.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
		}
	}()
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 128)
		for {
			n, peer, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			if _, err := udp.WriteTo(buffer[:n], peer); err != nil {
				return
			}
		}
	}()
	for _, streams := range []int{1, 8} {
		for _, reconnect := range []bool{false, true} {
			if err := probe(tcp.Addr().String(), "tcp", 20*time.Millisecond, streams, reconnect); err != nil {
				t.Fatal(err)
			}
		}
		if err := probe(udp.LocalAddr().String(), "udp", 20*time.Millisecond, streams, false); err != nil {
			t.Fatal(err)
		}
	}
	if err := probe("unused", "udp", time.Second, 1, true); err == nil {
		t.Fatal("invalid connect probe accepted")
	}
}
