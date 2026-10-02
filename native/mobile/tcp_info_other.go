//go:build !linux

package mobile

import (
	"errors"
	"net"
)

func readTCPInfo(net.Conn) (tcpSample, error) {
	return tcpSample{}, errors.New("TCP_INFO unavailable on this platform")
}
