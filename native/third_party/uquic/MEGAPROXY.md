Production package closure of github.com/refraction-networking/uquic at 837c7ce1b72bf07b2d29b3dabd5b45c60bec0f42. Original MIT license retained.

Two compatibility patches: honor the advertised active connection ID limit instead of a hard-coded four (Firefox advertises eight); disable unused QUIC TLS session events to retain uTLS 1.8.2. HTTPS TLS session resumption is unchanged. No QUIC 0-RTT or ticket cache is used.

Replace this local module with an upstream release once both fixes are available and production-dialer tests pass.
