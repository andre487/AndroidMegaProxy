Production package closure of github.com/refraction-networking/uquic at 837c7ce1b72bf07b2d29b3dabd5b45c60bec0f42. Original MIT license retained.

Compatibility patches: honor the advertised active connection ID limit instead of a hard-coded four (Firefox advertises eight); disable unused QUIC TLS session events to retain uTLS 1.8.2. HTTPS TLS session resumption is unchanged. No QUIC 0-RTT or ticket cache is used.

MegaProxy integration patches expose HTTP/3 `CanTakeNewRequest` for GOAWAY retirement and cancellation-aware DATAGRAM queue admission (`SendDatagramWithCancel`) through QUIC and HTTP/3. Cancellation removes the waiting send instead of leaving a background writer. HTTP/3 oversized-datagram errors subtract the quarter-stream-ID framing from the advertised payload limit. Passive `ConnectionStats` exposes smoothed RTT and cumulative sent-packet loss declarations with synchronized reads; MegaProxy samples these without packet capture or peer identifiers. The read-only `DatagramPayloadLimit` exposes the same current send limit used by DATAGRAM queue admission after handshake, so nested QUIC can verify its outer path MTU without sending oversized probe payloads. Original APIs remain available.

Replace this local module with an upstream release once the compatibility fixes and required integration APIs are available and production-dialer tests pass.
