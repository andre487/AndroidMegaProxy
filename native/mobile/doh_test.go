package mobile

import (
	"context"
	"encoding/binary"
	"net"
	"testing"
)

func TestEmptyAAAAResponseSuppressesIPv6Answer(t *testing.T) {
	query, id, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint16(query[len(query)-4:len(query)-2], 28)

	response, isAAAA, err := emptyAAAAResponse(query)
	if err != nil {
		t.Fatal(err)
	}
	if !isAAAA {
		t.Fatal("expected an AAAA query")
	}
	if binary.BigEndian.Uint16(response[0:2]) != id || binary.BigEndian.Uint16(response[6:8]) != 0 {
		t.Fatalf("unexpected empty response: %x", response)
	}
	if binary.BigEndian.Uint16(response[2:4])&0x8000 == 0 {
		t.Fatal("expected the DNS response bit")
	}
}

func TestEmptyAAAAResponseLeavesAQueryUntouched(t *testing.T) {
	query, _, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	response, isAAAA, err := emptyAAAAResponse(query)
	if err != nil || isAAAA || response != nil {
		t.Fatalf("unexpected A-query result: response=%x isAAAA=%v err=%v", response, isAAAA, err)
	}
}

func TestDoHEndpointOrderStartsWithLastSuccessfulProvider(t *testing.T) {
	c := newDoHPacketConnWithClient(
		config{DoHFallbackURLs: []string{"https://two.example/dns-query", "https://three.example/dns-query"}},
		nil,
		func(context.Context, string) (net.Conn, error) { return nil, nil },
		"https://one.example/dns-query",
		nil,
		nil,
	)
	defer c.Close()
	c.setActiveEndpoint(1)

	order := c.endpointOrder()
	if len(order) != 3 || order[0].index != 1 || order[1].index != 2 || order[2].index != 0 {
		t.Fatalf("unexpected endpoint order: %#v", order)
	}
}

func TestDoHEndpointsIgnoreDuplicatePrimary(t *testing.T) {
	c := newDoHPacketConnWithClient(
		config{DoHFallbackURLs: []string{"https://one.example/dns-query", "https://two.example/dns-query"}},
		nil,
		func(context.Context, string) (net.Conn, error) { return nil, nil },
		"https://one.example/dns-query",
		nil,
		nil,
	)
	defer c.Close()
	if len(c.endpoints) != 2 {
		t.Fatalf("expected two unique endpoints, got %#v", c.endpoints)
	}
}
