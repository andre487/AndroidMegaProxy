# Testing policy

[Русский](../ru/release-testing.md) · [Commands and setup](fastlane.md)

Use the smallest layer that observes the real behaviour. Do not require every feature on
four devices for every release, duplicate CI locally without a reason, or maintain committed
review reports. Historical release notes remain unchanged. Record material failures and the
scope of verification in the relevant PR/release; never include credentials or private captures.

## Local checks by change

| Change | Check |
| --- | --- |
| Documentation / artwork only | Local links, images, EN/RU consistency and claims against source; no APK build |
| Kotlin/UI/configuration | Relevant JVM/Robolectric tests during development; `bundle exec fastlane android android_checks` before delivery |
| Go transport/DNS | `bundle exec fastlane android native_tests`; add `native_integration` for dialer/auth/trust/server changes |
| Python/automation | `bundle exec fastlane android python_checks` (stdlib runtime, pinned style/test tools; Node.js for workflow JS tests) |
| TUN/JNI/service/permissions/Keystore/SAF | Existing API 26/35 integration suites in CI; local disposable emulator only when needed to reproduce |
| Broad or shared build change | All affected lanes; `test` alone excludes Python, Docker integration and devices |

Keep parsers, formatting, resource parity, navigation and state transitions in deterministic JVM/Go
tests. Compose interactions use the existing Robolectric harness with injected platform operations
and test Keystore; recorded service intents do not prove real service/JNI behaviour. Drain ordered
configuration writes and check failure state, not only `pending == 0`. Native tests use controlled
servers, never a personal/public proxy. Do not write tests that merely mirror trivial presentation code.

## Required CI

- PRs select suites against each suite's last successful ancestor in the same PR/base. Failed,
  cancelled and skipped jobs do not advance coverage. Missing history/API errors fall back to the
  whole PR diff; unknown/shared build inputs enable all suites. A diff error fails scope selection.
- Docs skip test jobs. Python-only changes run Python; Go test-only changes run Go. Native production
  and Android changes enable Android and both device suites. See `scripts/ci_changes.py` for exact paths.
- Every push to `main` and every release PR runs all suites. A full rerun reruns Change scope and
  forces all suites; failed-only reruns retain scope unless Change scope itself reruns.
- Keep `Change scope`, `Python tests and style`, `Native Go tests`, `Android tests and checks`,
  and `Android emulator API 26/35 / Device tests` as merge requirements. Native includes race tests
  and real GOST/OpenSSH Docker integration. Android includes JVM, lint, debug and unsigned release.
- API 26/35 run independently on Ubuntu 24.04 with KVM, cold boots and no snapshot cache. No software
  fallback or retry of runner/test failures. SDK installation and emulator startup may retry once
  before tests start. A failed download before the runner is infrastructure failure, not a test verdict;
  inspect it before a manual retry. Do not disable checksum/certificate verification to unblock CI.
- JUnit reports publish per suite immediately. A missing report or skipped suite is not a pass.
  PR APKs use no release secrets. Commands, artifacts and emulator prerequisites: [Fastlane](fastlane.md).

The device suites exercise real TUN/JNI TCP/UDP, HTTPS/SOCKS5/MASQUE, HTTPS preference/fallback,
all four Jump HTTP/3 availability pairs, QUIC presets/size/auth/trust, split routing, failover,
stop/restart and stop during SSH startup, VPN/notification permissions, real Keystore process
restart, subscription persistence and system DocumentsUI. The runner invokes cases in separate
instrumentation processes; Keystore seed/verify must remain separate. These tests do not establish
physical network handover, OEM power policy, every Always-on/lockdown mode or real IPv6 uplink support.

## Release acceptance: smoke first, expand by risk

Identify the exact candidate commit, version/ABI, APK SHA-256 and signer. Use full successful CI for
the candidate; a skipped PR suite is insufficient. Debug instrumentation supports but does not
replace signed-APK smoke testing. Publication/signing/VirusTotal are separate [release operations](release-automation.md).

A normal manual smoke should fit roughly 20–30 minutes on one suitable phone; this is a planning
budget, not a deadline that turns missing evidence into a pass. API-minimum/35 platform coverage
comes from CI. Use extra phones/APIs only for platform-specific changes or a reproducer.

| Scope | Required observations |
| --- | --- |
| Every release | Install/upgrade matching signed variant without uninstalling; profiles/secrets/routing survive. Connect, send real data from an ordinary app, stop and reconnect. Exercise the changed screen; scan app crashes/ANRs. |
| Routing, DNS, IPv6 or VPN lifecycle | Included/excluded ordinary app UIDs, global/split and MegaProxy shortcut/list synchronization; LAN bypass. Controlled DNS/UDP targets with positive/negative controls and origin/capture evidence. Stop during start/failover must not revive VPN. Real IPv6, network handover and Always-on/lockdown need suitable extra environments when affected. |
| Transport/auth/trust | Affected transports/hops only: binary payload round trip, correct/wrong/restored credentials, hostname/CA/SSH pin rejection, EOF/half-close, timeouts/cancellation, fallback. No direct fallback around a failed hop. Preserve documented GOST packet-size/IPv6/half-close limits. |
| Storage/subscriptions/import/export | Upgrade or changed-format migration, omitted versus empty secrets, cancelled/failed transfer without truncation, fresh subscription versus editor changes, process death/Keystore failure where affected. Preserve malformed/unreadable data; recovery is explicit. Never store credentials in saved-state bundles. |
| Test / Speed / VPN visibility | Test exit IP/country and independent UDP outcomes with accessible labels; copy IP. Speed on underlying/VPN routes: partial table/progress, flag/code, HTTP and TLS lines, download/upload and log. Use 10 MB for smoke, h3→h2→h1 fallback only where permitted; Back cancels, Home continues. RTT here is median TTFB of three requests, not passive proxy RTT. ProxyCheck daily-limit/failure/cache states use deterministic fixtures, no live quota consumption in unit tests. |
| UI/accessibility | Changed screens in EN/RU, narrow window and enlarged font; readable banner actions, correct switches versus multi-select checkboxes, TalkBack status labels, copy/select and working links. Full theme/orientation/device permutations only for a relevant defect. |
| Updates/releases | Affected source selection/consent/signature/ABI/version checks, download cancellation and installer result; use fixtures for malformed metadata/APKs. Verify tag/merge tree, signed APK hashes and completed VirusTotal reports when publishing. F-Droid recipe/reproducibility checks apply when that recipe/release changes. |

Use synthetic profiles and disposable fixtures. Back up and restore a phone's original app/network,
permissions and Always-on settings; destructive cases belong on disposable emulators. Network
success requires returned data and controlled origin evidence, not only “Connected”. Negative tests
need working controls before/after. Inspect code paths and use regression tests for dangerous cases
that cannot safely be reproduced on a personal device. Mark outcomes PASS, FAIL, BLOCKED,
NOT TESTED or N/A with a concrete reason; do not silently substitute source inspection for a device run.

Stop release acceptance for unexplained crashes, data loss, authentication/trust bypass, wrong
signer/ABI, broken forwarding or unverified affected migration/lockdown. Preserve the original failure,
fix the shared cause with a regression test, then rerun the reproducer and adjacent affected paths.
Restore devices and remove temporary access/files even after failure. No separate report directory is required.

## Keeping manual testing short

Budget about 20–30 minutes for the usual signed-APK smoke: upgrade and preserved settings,
real traffic, stop/reconnect, the changed screen and crash/ANR inspection. This is a planning
budget, not permission to skip a failed or blocked critical check.

- Use one suitable phone. Add another device/API only for an affected platform-specific path.
- Select only the affected rows above; exercise affected transports/hops rather than their full cross-product.
- Reuse exact-candidate CI for deterministic logic, transport matrices and API 26/35 integration;
  do not manually repeat those checks without a concrete gap or reproducer.
- Use 10 MB for speed smoke and synthetic fixtures for quota/error states; no repeated public
  speed/reputation requests just to fill a report. Reserve long soaks and physical network changes
  for changes that can affect them.
- Stop at a reproducible failure, retain the evidence and investigate. After the fix, rerun the
  reproducer and adjacent affected paths, not the entire manual matrix.
- No separate committed report or four-device inventory is required. State the tested scope,
  failures and material limitations in the relevant PR/release; always restore the device.

CI is unchanged: retain all existing suites, scope rules and independent API checks.
