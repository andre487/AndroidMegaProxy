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
