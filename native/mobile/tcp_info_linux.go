package mobile

import (
	"golang.org/x/sys/unix"
	"net"
)

// TCP_INFO on our own socket needs no elevated privileges. Read the original
// socket before TLS/SSH wrapping; never inspect TUN-side or multiplexed streams.
func readTCPInfo(conn net.Conn) (tcpSample, error) {
	raw, err := conn.(*net.TCPConn).SyscallConn()
	if err != nil {
		return tcpSample{}, err
	}
	var info *unix.TCPInfo
	var socketErr error
	err = raw.Control(func(fd uintptr) { info, socketErr = unix.GetsockoptTCPInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_INFO) })
	if err != nil {
		return tcpSample{}, err
	}
	if socketErr != nil {
		return tcpSample{}, socketErr
	}
	return tcpSample{rttMicros: info.Rtt, retransmits: info.Total_retrans}, nil
}
