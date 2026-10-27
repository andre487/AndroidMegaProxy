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

[Русская версия](../ru/native-benchmarks.md)
