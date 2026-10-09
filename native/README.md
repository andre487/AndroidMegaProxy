# Go dataplane

This module is bound into `megaproxy.aar` with gomobile. It embeds tun2socks/gVisor,
registers MASQUE (HTTP/3), HTTPS CONNECT and SSH `direct-tcpip` transports, protects every upstream socket through Android
`VpnService.protect`, verifies HTTPS proxy certificates and SSH host keys, and uses uTLS for HTTPS ClientHello control.
HTTPS with Jump nests a second HTTPS CONNECT transport inside the first, with independent TLS
verification, authentication and HTTP/2 sessions. Only the jump is dialed directly; it resolves
the destination proxy hostname. Application traffic counters exclude the intermediate tunnel.
SSH with Jump creates a nested SSH client through the jump session. SSH transports support TCP;
DNS is carried over DoH, while arbitrary UDP (including QUIC) is intentionally blocked.
`Start` borrows the JVM-owned TUN descriptor for the call and duplicates it with CLOEXEC on
entry. Go closes only its own duplicate; Java retains responsibility for the descriptor it passed.
The bridge passes the Android TUN MTU explicitly (currently 1400). Generated gomobile types are
compile-time JVM dependencies, so signature changes must compile on both sides.

Use the supported Fastlane commands from the repository root:

```shell
bundle exec fastlane android native_tests
bundle exec fastlane android native_fuzz
bundle exec fastlane android native_integration
bundle exec fastlane android debug_artifact
```

`native_tests` includes the Go race detector; `native_fuzz` runs a bounded 20-second parser campaign.
`native_integration` requires Docker and exercises production dialers against real GOST/OpenSSH
servers and a private HTTP/UDP origin. It runs in the native CI job, with no Android emulator.
See the [fixture and scenario details](../docs/en/fastlane.md#real-proxy-server-tests).
Android build lanes prepare `app/libs/megaproxy.aar` through `scripts/build-fdroid-native.sh`, which
installs the pinned gomobile/gobind version and uses the reproducible binding flags. Do not replace
that build path with `gomobile@latest`. Go dependencies are pinned in `go.mod` and `go.sum`.

Requirements and environment setup are in the root [README](../README.md#building-from-source)
and the [Fastlane reference](../docs/en/fastlane.md). Native tests use local servers and synthetic
file descriptors; they do not certify real Android TUN/JNI lifecycle behavior.

MASQUE uses uQUIC with protected UDP sockets and one shared HTTP/3 session.
TCP uses CONNECT streams; UDP uses CONNECT-UDP with context ID zero. DNS still
uses the existing DoH implementation. See the root README for GOST listener setup,
fingerprint versions and UDP limitations. The pinned local uQUIC package closure
retains uTLS 1.8.2; provenance and the two compatibility patches are recorded in
`third_party/uquic/MEGAPROXY.md`.
