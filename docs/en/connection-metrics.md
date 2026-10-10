# Connection quality metrics

The main screen shows **TCP RTT** and the **number of outgoing TCP retransmitted
segments observed in the last five minutes**, replacing connection-opening errors
and mixed connection-setup timings.

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

The old warning based on at least 75% failed connection openings is removed.
Existing timeout/reset-based suspected-blocking detection and recovery remain;
its message says the proxy may be blocked **or unavailable**. It is a heuristic,
not proof of filtering. RTT and retransmits do not trigger automatic failover.

MASQUE uses a UDP socket for QUIC, so kernel TCP RTT and retransmit readings are unavailable.
Traffic totals and rates still include forwarded TCP and UDP payloads.

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

[Русская версия](../ru/connection-metrics.md)
