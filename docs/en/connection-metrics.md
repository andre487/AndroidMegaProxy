# Connection quality metrics

The main screen displays payload totals/rates and passive metrics to the **first proxy**:
TCP RTT/retransmits for TCP transports, QUIC RTT/loss declarations for HTTP/3. An established
HTTP/3 session identifies the transport before any tunnel payload is sent.

- RTT comes from the kernel's smoothed TCP round-trip estimate (`TCP_INFO`). With
  multiple physical connections, the display is their arithmetic mean. It is not
  DNS lookup, TLS handshake, CONNECT duration, or end-to-end website latency.
  Idle connections retain the kernel's last estimate; this is not an active ping.
- Retransmits are counted once per physical TCP socket, including its final sample
  before close, in a rolling 300-second window. The VPN samples once per second
  while the process runs; deltas are assigned to their observation time. Android
  suspension can delay observations. No probe traffic or wake lock is added.
- Both metrics cover the phone to the **first proxy only**. SSH/HTTP2 channels and
  subsequent jump hops are not counted again. Local bypass traffic and standalone
  connection diagnostics are excluded from these TCP metrics.
- Retransmits describe the phone's outgoing segments, not a packet-loss percentage.
  They cannot measure all download losses or the proxy-to-destination path. Zero
  retransmits does not prove a healthy end-to-end connection. Failed TCP dials
  that never return a socket are not included.
- An unavailable kernel reading is shown as no data, not zero. RTT needs an open
  socket with an available estimate. Recent retransmit counts can remain after
  sockets close; without further readings they expire after five minutes.
- Counters reset for a new VPN core session. Late work from an old session and
  standalone diagnostics cannot alter the new session's counters.

Timeout/reset-based suspected-blocking detection says the proxy may be blocked **or unavailable**. It is a heuristic,
not proof of filtering. Recovery requires at least two independent proxy failures
within 60 seconds without a successful proxy tunnel between them. Multiple stream
errors from one QUIC session count once. A successful end-to-end proxy tunnel
clears the suspicion and its warning, including an exhausted-failover warning;
local bypass and the first jump hop alone do not prove recovery. A notice about
an actual switch to another profile is preserved. RTT and retransmits do not
trigger automatic failover.

MASQUE displays **QUIC RTT** and **outgoing QUIC packets declared lost in the last
five minutes** from uQUIC's passive connection statistics. RTT is the smoothed
estimate to the proxy, averaged across open physical QUIC sessions; multiplexed
TCP/UDP streams are counted once. A draining GOAWAY session remains included until
it closes. Its final loss sample is retained in the five-minute window, but RTT
requires an open session. Sessions that close unexpectedly are removed as well.
Loss counts use positive counter deltas at their observation time, including the
initial successful handshake sample. They are loss declarations, not TCP
retransmissions, all download losses or an end-to-end loss percentage. No packet
trace, probe traffic, wake lock or peer identity is collected. Unavailable values
stay empty; zero loss is shown only after a successful QUIC sample. The window,
background polling and VPN-session reset follow the same rules as TCP metrics.
Kernel TCP RTT/retransmits remain unavailable for QUIC.
Traffic totals and rates still include forwarded TCP and UDP payloads.

## VPN interface MTU

Settings → MTU offers 1280, 1400 (recommended default), 1500, or a custom integer
from 1280 to 65535 bytes. 1280 is the [IPv6 minimum link MTU](https://www.rfc-editor.org/rfc/rfc8200#section-5),
not a universal VPN default. 1400 leaves room for tunnel overhead; larger values are not necessarily faster.
The setting applies to all profiles on this device after reconnection, with the same MTU passed to Android
and the native stack. It is local and is not included in the portable configuration/subscription contract.
This sets the inner IP packet limit, not QUIC's negotiated datagram/path MTU.


## Negotiation diagnostics

Successful HTTPS proxy handshakes log the negotiated TLS version and cipher name,
ALPN (`h2`, `http/1.1`, `none`, or `other`), session resumption, whether certificate
verification is enabled, and certificate count. `hop=jump` / `hop=destination`
distinguishes proxy hops without addresses. `http_version=HTTP/1.1` or `HTTP/2`
on an established tunnel describes the actual CONNECT request protocol after
fallback. ALPN and `selected_connect_protocol` alone do not prove CONNECT support.
These fields describe the proxy transport, not applications' HTTPS traffic.

MASQUE session events include TLS version/cipher, ALPN `h3`, QUIC version, the selected
TLS/QUIC preset, certificate-verification setting and negotiated datagram/extended-CONNECT
capabilities. TCP/UDP tunnels report `protocol=http3`, local `quic_session`/`conn` numbers
and setup duration. Rejected CONNECT requests include HTTP status and failure class;
QUIC failures expose numeric error codes without peer-supplied text. GOAWAY and oversized
UDP drops are explicit. These events do not attest to an exact full-browser fingerprint.

SSH logs the initial negotiated key-exchange and host-key algorithms, plus cipher
and MAC in each direction (`c2s` client to server, `s2c` server to client).
`mac=aead` means integrity is provided by the cipher. Rekey negotiation is not
reported by the current library metadata. Vendor suffixes use domain-free aliases:
`@openssh.com` → `_openssh`, `@libssh.org` → `_libssh`, `@ssh.com` → `_sshcom`.
Unknown algorithm strings are replaced with `unknown`.

Negotiation events omit IPs, domains/SNI, usernames, passwords, key fingerprints,
certificate identities, wire session/connection IDs, key material and raw server banners.
Local sequential diagnostic numbers carry no peer identity. Do not
log complete TLS/SSH state objects. HTTP rejection messages use numeric status
codes, not server-provided reason text; connection-test providers use indices.


SOCKS5 profiles support TCP CONNECT and UDP ASSOCIATE, with optional credentials
(1–255 UTF-8 bytes each), on port 1080 by default. SOCKS5 adds no encryption or TLS
fingerprint. DNS defaults to DoH through the proxy; see [DNS modes](dns.md). The connection test also checks
end-to-end HTTP/3 over SOCKS5 UDP. TCP metrics cover proxy TCP connections, including
UDP control connections; they do not measure UDP loss or RTT.

## Test, Speed and VPN visibility

These are separate entries in the main menu; they measure different things:

| Screen | Meaning |
| --- | --- |
| Test | Proxy connection, HTTPS and exit IP/country; independent UDP/HTTP3 checks for supported transports. IP is selectable/copyable, status icons have spoken labels. If disconnected, a temporary test VPN may be used. |
| Speed | Active Cloudflare transfer over the underlying network and VPN separately; progressively filled table with IP, flag/code, HTTP and TLS on separate lines, latency, download and upload. |
| VPN visibility | Local Android/interface signals plus ProxyCheck public-IP reputation per available route. Local heuristics and remote reputation are not proof that all apps can detect the VPN. |

With split tunneling, select MegaProxy to measure the VPN route. It is selected by default;
existing application lists gain it once on upgrade. Explicitly removing it afterwards is preserved.

Speed uses 10/25/50 decimal MB per direction per route (10,000,000/25,000,000/50,000,000 bytes).
Two routes and both directions can consume up to four times the selected payload, plus overhead.
Default h3 falls back to h2 then h1; h2 permits h1 fallback, h1 stays h1. Fallback applies only to
initial metadata negotiation, not certificate errors, HTTP failures or failed payload transfers.
Latency is median **time to first byte** from three empty downloads, including request overhead;
it is not SSL-handshake time, an ICMP ping or the main screen's passive RTT.
Cloudflare's `h3.speed.cloudflare.com` endpoint is used for protocol-capable measurements.
The log records DNS/TCP/TLS/QUIC stages, protocol/fallback, HTTP status, connection reuse,
bytes and transfer timing; no response payloads or exit IP are added to the log.
Leaving the screen cancels requests and discards its results/log; Home/background alone does not.

ProxyCheck success is cached per public IP for 24 hours. Daily quota exhaustion, network failure
and unknown response format are distinct states; unavailable routes are shown separately.
Tests parse fixtures rather than contacting ProxyCheck. Full endpoint inventory: [README](../../README.md#privacy-and-security).

[Русская версия](../ru/connection-metrics.md)
