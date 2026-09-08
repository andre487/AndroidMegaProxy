# Test quality and critical-scenario review

Review snapshot: September 8, 2026. Counts and verification below describe that review.

This is a scenario/source audit, not a measured line-coverage or mutation-coverage report.
Counts of tests do not establish coverage of the Android VPN lifecycle.

## Findings addressed

- `MainUiTestBase.saved()` previously waited only for `pending == 0`, which also describes
  failed writes. It now drains the ordered configuration executor and asserts no failed
  write remains. Keystore cleanup runs in `finally`, including when an assertion fails.
- `MainScreenUiTest.command()` previously consumed arbitrary service commands until the
  expected action appeared. A wrong or duplicate start could pass. It now checks the next
  command, target component, and absence of extra commands after draining the executor.
  Negative command assertions use the same barrier. Stop and denied consent also verify
  the persisted desired-connection state.
- ConfigStore lacked direct integration coverage of several persistence contracts. Six new
  tests exercise reopening encrypted credentials, fresh GCM IVs, omitted versus explicitly
  empty secrets on import, deletion/reference/failover cleanup, stale reconnect tokens,
  and SSH jump trust isolation. They use the real serializer and crypto with synthetic keys.
- ConfigWriteQueue now additionally tests repeated Retry while a retry is pending, deletion
  before the initial queued write, and failure isolation between different profiles.

## Coverage assessment

| Area | Existing evidence | Important limit |
| --- | --- | --- |
| Native HTTPS/SSH/Jump | `native/mobile/*_test.go`: local TLS/SSH servers, payload round trips, auth/hop isolation, shared-session failures, cancelled opens, deadlines, closed dialers | HTTPS Jump failure cases currently use HTTP/1.1; the successful tunnel matrix covers both HTTP versions. No actual Android network handover. |
| Native startup and DNS | Borrowed/duplicated FD ownership, overlapping Stop/Start guard, DoH queue/deadline limits, bootstrap literals, parser fuzz seeds | Bridge tests use synthetic descriptors; no working Android TUN/JNI lifecycle. Fuzzing checks crashes, not all semantic outcomes. |
| JVM/native boundary | `NativeCallbackTest`, `ConnectionTestTargetTest`: descriptor range, fail-closed protection, stale callbacks, runtime profile/trust identity | Injected callbacks do not exercise generated JNI at runtime. |
| Persistence/import | Parser and merge tests, ConfigStore integration tests, ordered-write failure/retry tests | In-memory Keystore and Robolectric preferences do not model device key loss, disk-full commits, process death, or all legacy migrations. |
| UI | Real Compose screens, state assertions, navigation, permission/document results and persisted changes | Service intents are recorded, not executed. Only API 35 and English interaction fixtures; no screenshot/overlap assertions. |
| CI/launcher | `scripts/tests`: real temporary Git diffs, per-suite history selection, safe `gh` arguments, PR recheck, timeout without duplicate launch | Mocked GitHub responses do not certify live Actions permissions or branch-protection configuration. |

## Remaining priorities

1. **High: service lifecycle and recovery.** `ProxyVpnService` startup completion, Stop during
   startup, queued reconnect after Stop/onDestroy, network-change debounce and failover candidate
   selection have no direct behavioral tests. Extract the orchestration behind injectable core,
   scheduler and network inputs; assert stale starts cannot publish CONNECTED or revive a stopped
   VPN, and only valid/untried permitted profiles are selected. Keep real TUN/notification/OS
   delivery smoke checks on controlled devices outside required hosted CI.
2. **High: interrupted persistence and transfer.** Test commit failure after preferences have
   changed in memory, retry after returning to a screen, legacy migration fixtures, and loss of
   Keystore access. Define the expected recovery behavior before locking it into assertions;
   ordinary decryption errors currently yield empty secrets. Recreate the activity during import,
   export and SSH save; verify no duplicated operation and no credentials in saved state.
3. **Medium: broader UI configurations and negative paths.** Add Russian/narrow-window/large-font
   interactions and API-minimum coverage for platform-dependent screens. Test export cancellation,
   provider write failures and lost export payloads, not just successful export and malformed
   import. Navigation tests show that destinations open; they do not prove every control works.
4. **Medium: native failure matrix.** Extend HTTPS Jump rejection/cancellation checks to HTTP/2
   hops and assert the intended failure stage; accepting any non-null error can hide an unrelated
   setup failure. Add successful trust-store verification alongside self-signed rejection/bypass.

## Verification

Use the supported Fastlane commands in the [English](../en/fastlane.md) and
[Russian](../ru/fastlane.md) references. The review adds nine JVM tests; the suite contains
149 JVM tests, including 36 Compose interactions. Native race tests and Android JVM/lint/build
checks were run locally. No emulator, real Keystore, real VPN traffic or device profiling was used.
