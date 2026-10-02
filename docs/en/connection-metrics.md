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

[Русская версия](../ru/connection-metrics.md)
