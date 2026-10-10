# MASQUE α (HTTP/3)

MASQUE is experimental and marked **α** in the interface. Its implementation was merged
into `main` in [PR #70](https://github.com/andre487/AndroidMegaProxy/pull/70); published v1.0.3 APKs
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

UDP/53 queries entering the VPN are converted to DoH. Application-managed TCP DNS,
Private DNS and browser Secure DNS follow normal routing and are not rewritten to
the selected DoH provider.

## Fingerprints and limits

- uQUIC uses Chrome 146 or Firefox 116 presets, independently of TCP uTLS presets.
  Randomized uses Chrome with randomized extension/parameter order. Custom JA3
  needs TLS 1.3 suites and extensions 16, 43, 51, 57; QUIC parameter payloads use
  the Chrome preset. These approximate browser TLS/QUIC handshakes; HTTP/3 SETTINGS
  and congestion behavior remain those of the networking library.
- Manual JA3 selects a separate QUIC JA3 in each MASQUE profile; HTTPS uses the
  global JA3 in Settings. Both remain independent during mixed-transport failover
  and survive JSON export/import. ProxyList does not carry fingerprint settings.
- UDP must fit the negotiated QUIC datagram size and path MTU. GOST 3.3.0 has no
  reliable capsule fallback for oversized packets. Firefox's 1200-byte frame limit
  cannot fit a 1200-byte inner QUIC Initial plus framing; use Chrome for that traffic.
  Oversized outgoing packets are dropped whole, with one diagnostic per UDP flow;
  following smaller packets keep working. The advertised receive limit also bounds
  outgoing payloads to avoid GOST closing a flow on an echo-sized oversized reply.
- GOST 3.3.0 rejects IPv6 literal CONNECT-UDP targets. TCP IPv6 and local UDP bypass
  follow the profile's IPv6/routing policy. IPv4-only is the default.
- The stats card shows QUIC RTT and outgoing packets declared lost over five minutes.
  Kernel TCP RTT/retransmits are unavailable for the UDP socket. Payload
  totals and rates still include TCP/UDP. Session logs report `multiplexed=true`
  and the selected fingerprint; these do not prove an exact full-browser fingerprint.


## Diagnostic logs

Session events include TLS version/cipher, ALPN, QUIC version, negotiated capabilities,
fingerprint and local session/connection numbers for correlation. CONNECT rejections
include HTTP status and an authentication/rejection reason. GOAWAY retirement and UDP
size drops are explicit; normal Stop does not produce false UDP/DPI failures.
Peer-provided error text, credentials, target addresses, certificate identities and
wire connection IDs are omitted. Crash logs retain exception classes and stack frames,
without raw exception messages or arbitrary thread names. Library flow logs that bypass sanitization are disabled.
Repeated connection/DoH/SSH transport/UDP events are limited to 20 per event category per
10 seconds in the persistent log; the next emitted event reports the suppressed count.
Every event still reaches recovery/UI before this persistence limit. Session/lifecycle
and crash events remain unthrottled; disk rotation and the bounded writer queue apply.

A timeout/reset of an individual target on a healthy QUIC session is logged with
`scope=target dpi_hint=none`; it does not restart the whole VPN or trigger failover.
Application/half-close deadlines use `scope=local_deadline`. Proxy handshake and
transport failures retain recovery diagnostics. TCP half-close forwards FIN through
CONNECT while keeping the response direction open.

[Русская версия](../ru/masque.md)
