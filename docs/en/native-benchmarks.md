# Native core benchmarks

Run the Go benchmarks through Fastlane. The default suite requires no emulator, Docker,
credentials or external servers and leaves production code unchanged:

```sh
bundle exec fastlane android native_benchmarks
```

Defaults: ten samples per case, `200ms` per sample, `GOMAXPROCS=1`, no race detector or profiling.
Results overwrite `test-results/native/benchmarks/`: `bench.txt` in Go benchmark format,
`command.txt` with exact arguments and `environment.txt` with commit, working-tree status,
Go version and architecture. Use separate `output` directories to retain comparisons.

| Benchmark | Measured boundary |
| --- | --- |
| `CorePacketFilter` | Existing direct dispatcher versus strict header filtering, IPv4/TCP and IPv6/UDP, 64/1400/65535-byte buffers. Both strict levels share this filter. Buffers are reused: this measures steady-state inspection, not TUN input or buffer creation. |
| `CoreUDPRead` | Real `sourceUDP.Read` and `ReadFrom`, disabled/standard/advanced, 64/1400/65507-byte payloads. Includes the same in-memory copy in every mode and the production query semaphore in strict modes. Excludes initial flow admission. |
| `CoreUDPRecheck` | Standard mode's cache-hit and expired-cache paths with deterministic timestamps. Excludes `time.Now`; `CoreUDPRead` includes it. |
| `CoreAdmission` | New TCP/UDP flow dispatch, source-address formatting, goroutine scheduling and ownership query budget. Downstream handling is a stub, with no dial, handshake or packet forwarding. Disabled includes the same completion channel. |
| `SOCKS5ReadAllocation` | Existing production SOCKS5 UDP packet decoding against an in-memory socket. |

`ns/op`, `B/op`, `allocs/op` describe each boundary. `owner_queries/op` counts validator calls:
advanced UDP should have one per datagram; disabled has zero; standard only rechecks expired
flows. The validator returns immediately: **JNI, Android's socket-owner lookup, UID policy and
Binder costs are not simulated or included**. `MB/s` is processed-buffer throughput, not VPN
bandwidth. The suite does not measure encryption, real proxy transport, full gVisor forwarding,
concurrent saturation, radio behavior, power consumption or Android heap pressure.

## Compare modes and revisions

Close emulators and other CPU-heavy work. Keep the same host, Go version, CPU count, payload,
benchmark code and flags. Warm up once; for important changes collect before/after in alternating
order to expose thermal drift. A shared CI runner is unsuitable for strict timing thresholds;
these benchmarks are intentionally outside required checks.

```sh
bundle exec fastlane android native_benchmarks output:test-results/native/before
# Change revision or implementation, keeping the same benchmark definitions.
bundle exec fastlane android native_benchmarks output:test-results/native/after
benchstat test-results/native/before/bench.txt test-results/native/after/bench.txt

# Compare the three modes inside one run (use an installed benchstat).
benchstat -filter '.name:CoreUDPRead' -row '/bytes,/method' -col /mode \
  test-results/native/after/bench.txt
```

[Go's benchstat](https://pkg.go.dev/golang.org/x/perf/cmd/benchstat) reports medians,
confidence intervals and significance. Do not turn noisy nanosecond changes into regression
claims or average unrelated operations into one performance score. Keep the raw samples.

## Locate CPU and allocation hot spots

Profile a narrow workload separately; profiling changes timing. `profile:true` saves CPU and
sampled allocation profiles plus the matching `mobile.test` binary in the output directory:

```sh
bundle exec fastlane android native_benchmarks \
  'bench:BenchmarkCoreUDPRead/bytes=1400/method=ReadFrom/mode=advanced$' \
  count:1 benchtime:5s profile:true output:test-results/native/profile

go tool pprof -top test-results/native/profile/mobile.test test-results/native/profile/cpu.pprof
go tool pprof -top -alloc_space test-results/native/profile/mobile.test test-results/native/profile/heap.pprof
```

Use `go tool pprof -http=127.0.0.1:8080 ...` for interactive analysis. The heap profile includes
setup/runtime allocations too; `allocs/op` is the timed operation's allocation count.
For existing controlled local handshake/fallback benchmarks, select
`'bench:Benchmark(HTTP2ColdStart|DNSFallbackLatency)' count:3 benchtime:1x`; their artificial
delays test scheduling policies rather than Internet speed. `bench:BenchmarkSourcePacketFilter`
retains the historical header-only/full-copy comparison.

Actual strict-mode overhead needs a separate device experiment on Android 10+ (API 26 cannot
provide socket-owner validation), with the same device, private proxy/origin, flow count and
traffic pattern. Measure throughput, latency, CPU, memory and ownership-query frequency with
strict disabled, standard and advanced. These host microbenchmarks identify Go hot spots;
they do not predict that device result.


## Encrypted transport round trips

Transport benchmarks call dialers directly, without either strict source-validation level.

Run these in order to inspect MASQUE, HTTPS and SSH separately:

```sh
bundle exec fastlane android native_benchmarks 'bench:BenchmarkTransportMASQUE' output:test-results/native/masque
bundle exec fastlane android native_benchmarks 'bench:BenchmarkTransportHTTPS' output:test-results/native/https
bundle exec fastlane android native_benchmarks 'bench:BenchmarkTransportSSH' output:test-results/native/ssh
```

These opt-in benchmarks open only loopback listeners and use the production dialers with
local echo peers. No Docker, Internet access, personal proxy or supplied credentials is needed.
They are outside the default CPU-only suite and required CI timing checks.

| Transport | Workload |
| --- | --- |
| MASQUE | Warm QUIC streams at 64/1400/32768 bytes; CONNECT-UDP at 64/1200 bytes, with a fixed deadline or one renewed per exchange. |
| HTTPS | Warm CONNECT streams over HTTP/1.1, HTTP/2 and preferred HTTP/3 at 64/1400/32768 bytes. The benchmark verifies selection instead of silently measuring a fallback. |
| SSH | Warm encrypted direct-tcpip channel at 64/1400/32768 bytes, OpenSSH-style client profile, password authentication and a pinned ephemeral host key. The local peer is x/crypto/ssh, not OpenSSH. |

One operation is a sequential request/echo round trip. `MB/s` counts both directions.
Setup, certificate/key generation, authentication and the initial tunnel/channel open are outside
the timer, as is one warm-up exchange. Payload contents are checked after the loop; each exchange
checks its length/errors. SSH channels do not implement net.Conn deadlines; a watchdog closes
stalled stream benchmarks. This is not a test of SSH deadline support.

CPU and heap profiles cover **both client and echo server in one process**, including runtime,
crypto and library allocations. Per-operation allocation counts also include both endpoints.
Do not attribute server allocations to Android or compare these timings directly with the
in-memory `Core*` suite. The echo peers do not forward to a separate origin. Server-side request
validation, credentials and key checks remain active; TLS peers use test-only self-signed trust
exceptions. Profiles retain their normal encryption and TLS/SSH fingerprints.

For example, profile one narrow case separately:

```sh
bundle exec fastlane android native_benchmarks \
  'bench:BenchmarkTransportMASQUE/datagram/bytes=1200/refresh-deadline=false$' \
  count:1 benchtime:5s profile:true output:test-results/native/masque-profile
```

Compare raw samples with benchstat as above. Unchanged stream scenarios are useful controls for
host drift: a timing change shared by controls is not evidence of an optimization's effect.
These are warm single-flow loopback measurements, without packet loss, congestion, radio changes,
reconnect storms, jump chains, sustained rekey/rotation workloads, TUN or JNI. Real GOST/OpenSSH
interoperability remains the separate `native_integration` lane; Android integration remains the
device lanes. These results do not establish which transport is fastest on a phone.

[Русская версия](../ru/native-benchmarks.md)

## Linux comparison with sing-box

Build the production `mobile.Start`, sing-box **1.14.4** (`with_gvisor`) + **1.15.0-alpha.13** (`with_gvisor,with_quic`) and GOST **3.3.0**
(commit `cb76f63754768c7b5d68895a0d51635b0141b80f`) with one installed Go toolchain.
`GOTOOLCHAIN=local`, `CGO_ENABLED=0` and `-trimpath` are fixed. External tool dependencies do
not enter `native/go.mod`. Use Linux and the Go version from that file:

```sh
bundle exec fastlane android native_compare_build output:/tmp/core-comparison-bin
bundle exec fastlane android native_compare binaries:/tmp/core-comparison-bin samples:5 seconds:3
# Select one transport when investigating a result:
bundle exec fastlane android native_compare binaries:/tmp/core-comparison-bin transport:https-h2
```

Run workloads on a dedicated Linux VM with at least two available CPUs, `/dev/net/tun`,
`sudo`, `iproute2` (including `ss`), **iperf3 3.22.1**, `openssl`, `ssh-keygen` and `sshd`.
Both client and server use the same resolved executable. Select a private installation with
`iperf:/opt/iperf-3.22.1/bin/iperf3` in Fastlane or `--iperf /opt/iperf-3.22.1/bin/iperf3`
in the runner. Other versions are rejected before setup: old 3.16 can segfault during thread
cleanup. The selected version, path and SHA-256 are recorded in `environment.json`.
No host computer changes are needed. Binaries may be built on another Linux machine of the
same architecture. Copy their directory plus `scripts/compare_cores.py` to the VM. Without
Fastlane there, use the same runner directly:

```sh
sudo unshare --net -- python3 compare_cores.py run \
  --binaries /tmp/core-comparison-bin --output /tmp/core-comparison-results \
  --samples 5 --seconds 3 --transport all
```

| Transport | MegaProxy | sing-box counterpart | Tunneled UDP |
| --- | --- | --- | --- |
| `socks5` | SOCKS5 | SOCKS5 | yes |
| `https-h1` | TLS + HTTP/1.1 CONNECT | TLS + HTTP/1.1 CONNECT | no |
| `https-h2` | TLS + HTTP/2 CONNECT | 1.15 alpha: HTTP/2 CONNECT | no |
| `https-h3` | HTTPS with HTTP/3 preferred | 1.15 alpha: HTTP/3 CONNECT + CONNECT-UDP | yes |
| `masque` | mandatory HTTP/3 CONNECT/CONNECT-UDP | 1.15 alpha: HTTP/3 CONNECT + CONNECT-UDP | yes |
| `ssh` | SSH | SSH | no |
| `https-jump-h1` | two HTTPS/1.1 hops | two HTTP outbounds with detour | no |
| `https-jump-h2` | two HTTPS/2 hops | 1.15 alpha: HTTP/2 detour | no |
| `ssh-jump` | two SSH hops | two SSH outbounds with detour | no |

`https-jump-h3` is excluded from both engines until GOST fixes its fatal handling of oversized
nested-QUIC DATAGRAMs. The exclusion and reason remain explicit in metadata and the report.

Original pairs retain stable sing-box 1.14.4. HTTP/2, HTTP/3 and MASQUE pairs use
pinned **1.15.0-alpha.13**, a prerelease: HTTP version is explicit and version fallback
is disabled on both chain hops. MASQUE here means CONNECT-UDP (RFC 9298), not CONNECT-IP.
Both binary versions and hashes are recorded; do not pool their results.
Mixed HTTP-version chains, fingerprint
variants and authentication variants are not a separate matrix. HTTPS uses the ordinary
CHROME_ANDROID profile; SSH peers are pinned and use the same OpenSSH banner, Curve25519
and ChaCha20-Poly1305 on both clients. SOCKS5 is anonymous; HTTPS uses disposable Basic
credentials and SSH uses a disposable Ed25519 client key.

The runner rejects PID 1's network namespace. A private outer namespace hosts the shared
SOCKS5 server, GOST TLS/HTTP2/HTTP3 listeners, two separate OpenSSH daemons, echo and iperf3.
The existing system SSH daemon/configuration is untouched. New daemons allow forwarding
only to benchmark destinations, and do not allow shell sessions. Exit listeners in jump
chains bind to the server namespace's loopback: the client cannot reach them directly.
TLS certificates, SSH keys, credentials and client configurations live only in a temporary
private directory, removed after cleanup. Both clients validate the private certificate
through `SSL_CERT_FILE`; system CA stores stay unchanged. Secrets are excluded from results.
HTTP versions have dedicated listeners/ports; HTTP/3 ports have no TCP fallback listener.

Only the numeric workload destination routes through TUN. The proxy keeps its connected
veth route; no system routes change and no external origins, DNS, personal proxies,
Android/JNI or strict source validation participate. TUN MTU (default 1400), underlay veth
MTU (default 1500), IPv4, `GOMAXPROCS=1` and one core CPU are fixed. Other CPUs host servers,
generators and the coordinator.
sing-box uses `gvisor`; other stacks are outside this comparison. Direct traffic bypasses
both TUN and proxy, providing a control rather than an equivalent third core.

TUN and underlay MTUs are independent (`mtu` / `--mtu` and `link_mtu` / `--link-mtu`).
Nested QUIC needs encapsulation space in the underlay: forcing both to 1400 caused an
HTTP/3 jump probe timeout in this fixture. Both MTUs are recorded; failures with a reduced
underlay MTU must not be mixed into the normal-link performance table.

For each supported pair, measure TCP upload/download and connection+64-byte echo with 1/8
streams, and TCP echo latency. UDP-capable transports also get 1/8-stream UDP at a fixed
20 Mbit/s total offered load and UDP echo latency. UDP via protected direct sockets is
excluded. iperf3 omits one warm-up second. Every measured scenario gets a fresh, warmed core
process, so a failure cannot poison the next scenario. Workload order stays fixed. Pair order
reverses on alternate samples. Failed
workloads are not retried: their errors are saved, the matrix continues, and the final exit
status is nonzero. Medians use passed samples only; passed/total counts remain visible.
Every proxied measurement checks bidirectional TUN counters.

A fresh output directory holds `environment.json`, `support.json`, logs, per-measurement
JSON, `samples.json`, `samples.csv` and median `summary.md`. Metrics include receiver goodput,
UDP loss/jitter, operations/s, p50/p95/p99, core CPU and sampled RSS. CPU includes iperf warm-up;
server/generator resources are not attributed to the core. Existing data is never overwritten.
On a two-vCPU VM, servers and generators compete for the second CPU, especially with encrypted
chains. Inspect raw-sample spread and the direct control. Results do not establish an absolute
core limit, UDP maximum capacity, phone energy use or Android performance. This manual benchmark
is outside required CI and has no performance thresholds. The full matrix has 130 workloads
per sample; the default five-sample run takes tens of minutes, plus any failed-workload timeouts.

Measurement JSON retains exit codes and names signals such as `SIGSEGV` explicitly. If a fixture
server exits, the run aborts without retrying; `summary.md`, `samples.json` and `status.json`
retain the partial results, with `complete: false` and the failing server/exit status. A finished
matrix with workload failures has `complete: true` and still exits nonzero.
