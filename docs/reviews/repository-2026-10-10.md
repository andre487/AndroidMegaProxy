# Repository review — October 10, 2026

Base: `a01624f1238a3a62b6bd91071aee548ef6dcf655`, after the merge of
[MASQUE PR #70](https://github.com/andre487/AndroidMegaProxy/pull/70).
The findings below are addressed on a separate branch from that main commit.
This is a source and scenario review, not a measured coverage report or release acceptance.

## Scope

| Area | Reviewed contracts and materials |
| --- | --- |
| Android | Screens/navigation, EN/RU resources, model validation, configuration persistence/import/export, application-owned writes, VPN lifecycle/callbacks/recovery, Always-on, diagnostics/privacy, update discovery/download/verification/install, feedback/crash handling, manifest/backup/FileProvider boundaries. |
| Native | Production HTTPS/HTTP2, SSH/jump and MASQUE dialers, authentication/trust, cancellation/half-close, deadlines/admission, DNS/bootstrap, JNI/TUN ownership, TCP/QUIC accounting, fingerprint construction, diagnostic classification and HTTP/3 probes. |
| Tests | JVM/Compose contracts, persistence and transfer integration tests, native transport regressions, GOST/OpenSSH fixtures, API 26/API 35 device runner and scenarios. Existing gaps remain distinct from passing test counts. |
| Build/distribution | Gradle, Fastlane, pinned dependencies/toolchains, release signing boundaries, VirusTotal publication flow, release automation, selective CI/history, trusted artifact/check publishers, local launch/JDK discovery scripts. |
| Documentation/assets | README, privacy policy, EN/RU guides and command references, release-testing protocol, configuration schemas/examples/checksums, listing text, icons/feature graphics and screenshots. Historical changelogs and dated reviews retain their historical meaning. |
| Vendored code | Provenance/licenses and MegaProxy's uQUIC compatibility, GOAWAY, DATAGRAM cancellation/size and passive-statistics patches. This does not constitute an independent line-by-line security audit of the entire third-party QUIC implementation. |

The configuration schema pin remains `9425fac3eb347186f441be60d91ee656eca91d67`;
real Android exports continue to validate against both vendored schemas. No dependencies,
proxy server configuration, release version or signing material were changed by this review.

## Findings addressed

| Finding | Consequence before the fix | Fix and verification |
| --- | --- | --- |
| TCP FIN was hidden by wrappers | An origin waiting for request EOF could never send its reply through buffered HTTP/1, SSH tracking or HTTP/2. | Preserve `CloseWrite`/`CloseRead` through wrappers; HTTP/2 sends END_STREAM without closing the response. `TestTCPHalfClosePreservesReplyThroughWrappers` failed for all three paths before the fix and now passes, including tun2socks tracking. |
| HTTP/2 GOAWAY replacement closed live streams | Installing a replacement session aborted unrelated tunnels still using the draining connection. | Use the library's graceful `Shutdown`, retain ownership for immediate Stop, and prune closed sessions. `TestHTTP2GoAwayPreservesActiveTunnelDuringReplacement` verifies old and new streams after a real GOAWAY. |
| SSH Close waited behind a handshake | Stop could wait for the pending server banner/handshake timeout. | Cancel the in-progress handshake before waiting for the session mutex. `TestSSHCloseCancelsPendingHandshake` reproduces the previous delay and verifies bounded cancellation. |
| Old MASQUE errors could restart a healthy replacement | A retired session's timeout/reset could be classified as proxy interference after a new session was healthy. | Classify against both the originating session and the current replacement; preserve target/local-deadline scope for TCP/UDP. `TestRetiredSessionFailureDoesNotRestartHealthyReplacement` checks both healthy and failed replacement states. |
| Startup diagnostics counted as runtime failures | Queued diagnostics from startup or a previous generation/profile could affect recovery of the current VPN. | Gate runtime handling on the current generation, running state and active profile; retain a startup blocking signal when a later summary hides it, while prioritizing authentication/trust failures. Existing real MASQUE timeout/failover device scenarios exercise this path in CI. |
| Different ConfigStore instances lost concurrent writes | Per-instance `@Synchronized` did not protect shared profile storage or first key creation. Eight concurrent additions left only one added profile in the reproducer. | Use a shared process lock for profile read/modify/write, migration, key creation, global settings and reconnect-token replacement/clear. `independentStoreInstancesDoNotLoseConcurrentProfileAdds` now preserves all eight additions. |
| Unreadable secrets became empty saved values | A rename or unrelated setting change could permanently replace encrypted credentials after a Keystore/decryption failure. Reads could create a replacement key. | Keep opaque ciphertext until explicitly replaced, avoid creating keys during reads, show localized recovery errors and reject exports that include unavailable secrets. Tests cover metadata writes, temporary key loss/recovery, omitted import secrets and explicit clearing. Opaque values never enter portable exports or Android saved state. |
| Malformed profile JSON was overwritten | Reading a damaged store silently replaced all profiles with a default profile. | Preserve raw storage, expose a non-connectable placeholder, reject ordinary writes/exports and allow explicit backup import. `malformedProfilesRemainUntouchedUntilExplicitBackupImport` verifies preservation and recovery. |
| Fresh portable import could lose profile IPv6 settings | Reading global settings after an import could run legacy IPv6 migration over the imported profiles. | Mark the per-profile migration complete when portable global settings are applied. `portableImportPreservesPerProfileIpv6BeforeGlobalSettingsAreRead` exercises import followed by the global-settings read. |
| Disabled update checks retained a pending dialog | A previously detected update could still surface after automatic checks were disabled. | Suppress pending automatic prompts while disabled; manual checks remain available. `disablingChecksSuppressesPreviouslyDetectedDialog` covers the transition. Background-check timestamps use the shared system-locale formatter. |
| Authenticated release-note requests followed redirects | The release automation's HTTP client could forward authentication to an unexpected redirected destination. | Reject redirects using the standard urllib handler; test 301/302/303/307/308 with synthetic credentials. No additional HTTP dependency. |
| Launch/JDK helpers bypassed supported tooling | The launch script used an unpinned gomobile invocation; the language-server helper assumed a Homebrew architecture-specific JDK path. | Launch builds use Bundler/Fastlane; JDK lookup reuses the existing discovery helper. Shell syntax and normal Android build checks pass. |
| Current documentation and store assets lagged main | MASQUE still appeared unmerged, DNS wording overstated interception, the vendor patch inventory was incomplete, and Russian screenshots showed English screens. | Synchronize current EN/RU guidance, clarify UDP/53 versus application-managed TCP/encrypted DNS, refresh the privacy policy and listing text, document passive QUIC statistics, and replace ten screenshots with actual EN/RU API 35 captures using fictitious profiles. |
| Device UDP probes assumed reliable single-datagram delivery | The first CI run passed API 26, but API 35 timed out on the first 512-byte probe, before sending the oversized packet. The tunnel remained connected; the evidence does not identify the packet-loss location. | Acknowledge uniquely tagged idempotent echo probes within the original 15-second deadline, ignoring late duplicates. Isolate oversized-flow fixtures from unrelated applications and require exactly one established UDP association, so replacing a broken flow cannot hide behind probe retransmission. Test failures still fail CI and are never rerun automatically. |

## Verification

Local results for the final production changes:

- `bundle exec fastlane android native_tests`: **111 tests passed**, race detector enabled.
- `bundle exec fastlane android android_checks`: **213 JVM tests passed**, lint, debug APK
  and verified unsigned release APK passed, using JDK 21.
- `bundle exec fastlane android python_checks`: **58 tests passed**, pinned Black/isort checks passed.
- `bundle exec fastlane android native_fuzz`: passed the bounded 20-second campaign,
  two workers, 31,715 executions after the saved baseline corpus.
- Shell syntax checks and `git diff --check`: passed.
- Ten store screenshots were captured on a separate API 35 emulator and visually reviewed;
  the temporary capture test was removed and that emulator stopped afterward.

Local Go was 1.27.0; CI uses the 1.26.3 version declared by `native/go.mod`.
Local GOST/OpenSSH integration could not complete because Docker image preparation failed
against Docker Hub. This is **BLOCKED locally**, not a passing integration result;
the PR's native CI job runs the real-server suite on Linux. The PR must also pass both
independent API 26/API 35 integration jobs before merge. Consult the PR's actual checks
for their results rather than inferring device coverage from local JVM/native counts.

The [initial CI run](https://github.com/andre487/AndroidMegaProxy/actions/runs/38064716734)
passed 112 native tests, 30 real GOST/OpenSSH scenarios, 213 JVM tests and 58 Python tests.
API 26 passed 18 scenarios; API 35 passed 18 of 19 and failed the initial UDP probe above.
That failure remains recorded. The revised probe/association contract requires a new
CI run on the changed test code; it does not turn the original failure into a pass.

## Remaining boundaries

- MASQUE remains **α**. GOST 3.3.0 IPv6-literal CONNECT-UDP support, Firefox's inability to
  fit an inner 1200-byte QUIC Initial plus MASQUE framing, and the unavailable reliable
  capsule fallback remain documented limits, not fixes claimed by this review.
- Browser presets approximate TLS/QUIC handshakes; passing traffic and selecting a preset
  do not establish an exact full-browser HTTP/3 fingerprint.
- UDP/53 interception does not rewrite application-managed TCP DNS, Android Private DNS
  or browser Secure DNS. Their actual path depends on normal VPN/routing rules.
- Physical OEM phones, real cellular handover, independent external DNS/leak capture,
  Direct Boot/lockdown acceptance, 16 KiB page-size devices, long background/energy soak,
  signed-release migration and F-Droid reproducibility were **not tested** by this source review.
  Follow the [release-testing protocol](../en/release-testing.md) for those gates.
- Existing [test-quality](test-quality.md) and [privacy-policy](privacy-policy.md) reviews
  remain dated snapshots. Green CI and this report do not certify a new public release.
