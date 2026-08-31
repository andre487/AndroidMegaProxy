package mobile

import (
	"encoding/binary"
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
