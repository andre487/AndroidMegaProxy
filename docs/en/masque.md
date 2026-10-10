# MASQUE α (HTTP/3)

MASQUE is experimental and marked **α** in the interface. Its implementation is in
[PR #70](https://github.com/andre487/AndroidMegaProxy/pull/70); published v1.0.3 APKs
contain HTTPS/SSH only. Use an identified build containing that implementation.
PR APKs are test builds; see [installation](installation.md#test-builds-from-pull-requests).

## Configure GOST

The client uses TCP CONNECT and CONNECT-UDP over one shared HTTP/3 session with
Basic authentication. For GOST 3.3.0 use the `http3` listener and `masque` handler;
`h3` is a different transport. Enable QUIC datagrams and open the selected UDP port.

A minimal server configuration with a valid certificate:

```json
{
  "services": [{
    "name": "masque",
    "addr": ":443",
    "handler": {
      "type": "masque",
      "auth": {"username": "USER", "password": "PASSWORD"}
    },
    "listener": {
      "type": "http3",
      "metadata": {"enableDatagrams": true},
      "tls": {
        "certFile": "/path/to/fullchain.pem",
        "keyFile": "/path/to/privkey.pem"
      }
    }
  }]
}
```

Replace the credentials and certificate paths, restrict access to this file, and
run `gost -C gost.json`. The certificate must cover the proxy hostname and have a
chain trusted by Android. Arrange certificate renewal and GOST restart separately.

In MegaProxy select **MASQUE α (HTTP/3)**, enter the hostname, UDP port (default 443)
and credentials. Keep certificate verification enabled. Run **Test**, then
**Connect**. The test should report the server's exit IP and country.
If QUIC times out, verify UDP reachability, cloud/host firewall rules and any VPN
on the emulator's host; a working TCP/443 connection does not establish UDP access.

Global/per-app routing, local-network bypass, traffic accounting, DoH/fallback,
connection diagnostics, reconnect/failover and encrypted credentials apply to
MASQUE. JSON uses `proxy.type: "MASQUE"`; ProxyList uses
`masque://user:password@host:port`. Jump modes remain HTTPS/SSH only.

## Fingerprints and limits

- uQUIC uses Chrome 146 or Firefox 116 presets, independently of TCP uTLS presets.
  Randomized uses Chrome with randomized extension/parameter order. Custom JA3
  needs TLS 1.3 suites and extensions 16, 43, 51, 57; QUIC parameter payloads use
  the Chrome preset. These approximate browser TLS/QUIC handshakes; HTTP/3 SETTINGS
  and congestion behavior remain those of the networking library.
- UDP must fit the negotiated QUIC datagram size and path MTU. GOST 3.3.0 has no
  reliable capsule fallback for oversized packets. Firefox's 1200-byte frame limit
  cannot fit a 1200-byte inner QUIC Initial plus framing; use Chrome for that traffic.
- GOST 3.3.0 rejects IPv6 literal CONNECT-UDP targets. TCP IPv6 and local UDP bypass
  follow the profile's IPv6/routing policy. IPv4-only is the default.
- QUIC uses UDP, so kernel TCP RTT/retransmit metrics are unavailable. Payload
  totals and rates still include TCP/UDP. Session logs report `multiplexed=true`
  and the selected fingerprint; these do not prove an exact full-browser fingerprint.

[Русская версия](../ru/masque.md)
