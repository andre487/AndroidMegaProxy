# Release verification

This is the release acceptance procedure for MegaProxy. Run it against an identified
candidate APK, not an unspecified checkout or a debug build. A green CI run is an
input to this procedure, not a substitute for device testing.

The method incorporates the independent 5G Proxy Client, DNS Resolver, TG WS Proxy
and White List reviews in the maintainer's `f-droid-review` workspace: artifact
identity, ordinary-app probes, positive/negative controls, independent network
evidence, recovery checks and explicitly bounded conclusions. Their old results
do not establish anything about MegaProxy or a new Android build.

## Result and evidence rules

Use **PASS**, **FAIL**, **BLOCKED**, **NOT TESTED**, or **N/A** per scenario and device.
N/A requires a product/platform reason; lack of equipment or time means NOT TESTED.
A source inspection or JVM test cannot fill a device-test cell. Record the exact
scope of a partial result rather than marking an entire feature PASS.

Each record contains: case ID, UTC time, candidate commit/APK hash, device/OS build,
preconditions, action, expected result, observed result, evidence paths, verdict
and restoration result. Link screenshots to the relevant step. Screenshots of
“Connected”, an ADB exit status and a job being scheduled are not network evidence.
Use a unique request token and correlate an ordinary app's response with origin
logs or capture. Negative network tests require working controls before and after;
repeat material leak/authentication failures at least twice with fresh tokens.

Keep raw exports, private keys, personal profile names, device/account identifiers
and packet captures in a local private evidence directory (0700; sensitive files
0600). Commit only reviewed, redacted records and synthetic fixtures. No automatic
VirusTotal uploads of private review candidates, public services, issue posts or
release publication as part of this review. The authorized tag-release workflow
scans public signed APKs separately; see [release automation](release-automation.md).

## Preparation and release gates

| ID | Procedure and acceptance evidence |
| --- | --- |
| A01 | Record clean candidate source commit, dirty paths, versionName/code for every APK, min/target SDK, ABI, toolchain and dependency lockfiles. Preserve unrelated user changes. A docs-only branch must identify the separately built source commit. |
| A02 | Run `bundle exec fastlane android test`, `bundle exec fastlane android python_checks` and `bundle exec fastlane android native_integration`; retain commands, statuses, test counts and exact-commit CI links. `test` runs native/JVM/lint/build checks only. Also require both API 26/API 35 device suites: `bundle exec fastlane android device_test_build`, then `bundle exec fastlane android device_tests api:26` and `bundle exec fastlane android device_tests api:35`, or their full CI results. Use accelerated cold boots; never retry failed tests to get green. PR suites skipped by change filtering do not establish candidate coverage; release-branch CI runs all suites. Debug TUN/JNI tests and GOST/OpenSSH integration support, but do not replace, signed-APK acceptance. |
| A03 | Build with `bundle exec fastlane android release_artifacts` using JDK 21 and the normal release signer. Retain SHA256SUMS, `apksigner verify --verbose --print-certs`, `aapt dump badging`, merged permissions/components and ABI inventory. Do not bump production version codes just to arrange an update test. |
| A04 | Compare source and merged manifest permissions; explain library additions. Review exported components, intent handlers, FileProvider paths, backup rules, SDKs, executable assets, dynamic loading, APK downloads and dependency/licenses changes. Check privacy policy and listing against behaviour. |
| A05 | For an actual F-Droid candidate, pin its recipe/upstream/fdroidserver revisions and official buildserver image digest; run official metadata checks, scanner and unchanged recipe. Where configured, run official reproducibility/signature-copy verification against the matching upstream APK. A normal Gradle build is not this result. If no applicable recipe/release exists, record that explicitly. |
| A06 | Inventory two phones and two emulators anew: model, Android/API, OEM build, patch, ABI, page size, screen/font scale, language, installed package/version/signer, VPN/Private DNS/Always-on/lockdown, permissions and connectivity. One emulator must use the declared minimum API (currently 26); the other uses API 35, matching the second required CI suite. Newer APIs are additional coverage. 4 KiB testing does not establish 16 KiB support. |
| A07 | Save existing app configuration and original system settings before mutation. Use synthetic profiles and dedicated ordinary test apps; on a personal phone restrict routing to those apps. Obtain applicable authorization for phone reboot/network changes. Do not clear/uninstall a pre-existing installation or change its lock credential. |
| A08 | Establish controlled HTTP/HTTPS origins, GOST HTTPS/SOCKS5/MASQUE and OpenSSH endpoints, independent jump credentials/keys, HTTPS subscription feeds, DoH, logs and a capture point. Enable UDP and provide UDP echo/HTTP3 origins; for H12–H15 provide TCP HTTPS and UDP MASQUE on the same numeric port of each hop. Prove direct baseline and fixture health. Loopback/ADB reverse alone proves neither physical handover nor DNS leak resistance. Never route personal apps through the fixture. |
| A09 | Audit the release automation evidence separately from device results: all required suites succeeded for the exact release head/base; the actual tagged merge has those two parents and the tested head tree. Check version/ABI codes and both locales of notes. For an authorized tag release, match every signed APK hash to SHA256SUMS and completed VirusTotal analyses, Markdown/JSON reports and release-note links. API errors/timeouts or malicious/suspicious verdicts must block publication. Exercise failures with Python fixtures, not live release creation; this procedure does not authorize merge/tag/publish. |

## Priorities when the review budget is limited

Complete artifact identity, basic forwarding on every supported test device,
trust/authentication rejection, controlled DNS/UDP/routing checks, recovery and
applicable migration first. Preserve restoration and evidence collection as
mandatory final steps. Defer rare UI combinations, exhaustive permutations and
long soaks explicitly; keep their cells NOT TESTED. A smaller review does not
turn unexecuted release gates into PASS. Do not start a new scenario if it leaves
insufficient time to restore personal devices and remove temporary access.

## Four-device execution matrix

Run I01–I06, P01–P07, T01–T10, C01–C05, L01–L11, D01–D05, U01–U04 and E01–E03
on all four devices where applicable; add S01–S04 for SOCKS5 and H01–H15 for
MASQUE/HTTPS HTTP/3 preference. Record transport and hop variants as separate subcases. Run advanced
capture, Direct Boot, forced Doze and destructive-data cases on the dedicated emulators first. Repeat applicable
cases on phones only within the recorded authorization. A phone exception remains
visible in the matrix; an emulator result never silently substitutes for it.

### Installation, permissions and UI

| ID | Steps → required observation |
| --- | --- |
| I01 | Clean install on an empty emulator/test profile; cold launch twice; traverse every main/settings screen. On phones preserve existing data. Read back `pm path` APK and compare SHA-256 with the selected signed artifact on every device. |
| I02 | Upgrade a previous signed release containing synthetic profiles to the candidate. Verify profiles, secrets, active/Always-on selection, routing, counters/preferences and updater source; start a real request afterward. Retain both artifact identities. Same-version reinstall is not a migration test. |
| I03 | Deny then grant VPN consent; cancel connection; revoke consent by switching VPN where safe. Check UI, actual VPN/service state and traffic; stale callbacks must not claim a connection. Denied notification permission must have a usable explanation and settings path. |
| I04 | Start/stop/reconnect from visible controls and notification; rapid repeated taps, Back, Home, task removal, activity recreation and rotation. No duplicate tunnel, stale spinner or unintended connection after Stop. Check actual traffic after each state transition. |
| I05 | English and Russian; narrow screen, large font, portrait/landscape, dark/light theme, keyboard and foldable outer/inner display. Scroll long dialogs and profile names, check all buttons/labels are reachable. Check IEC/SI display, Latin MiB input labels, limits, timestamps and empty/error states. |
| I06 | Open the GitHub link beside the version and the Settings privacy-policy link in EN/RU, including narrow/large-font layouts; verify their repository/policy targets. With no browser handler, show a localized error without crashing or changing VPN state. Check connection-visibility warnings against actual Android VPN/Always-on/lockdown settings. |

### Profiles, persistence and trust

| ID | Steps → required observation |
| --- | --- |
| P01 | Create, edit, clone, select, reorder and delete synthetic HTTPS, HTTPS Jump, SSH, SSH Jump, SOCKS5 and MASQUE α profiles. Exercise HTTPS HTTP/3 preference separately (H12–H15). Cancel a new draft. Restart after saving; check independent profile fields and active/Always-on references. Delete only test profiles. |
| P02 | Import supported URI/text and full JSON; export with secrets excluded and explicitly included; reimport and make a real connection. Check omitted versus empty secrets, stable IDs, duplicate merge, old schema, unknown future schema, malformed/truncated/oversized data and cancelled SAF picker. No partial destructive import or silent secret loss. Include explicit unknown JSON transport and URI scheme (reject, never silently HTTPS), omitted legacy type (HTTPS default), ignored browser/unknown fields reported once without values, and exported-schema validation against vendored MegaProxyConfig. Unsupported fields do not round-trip. |
| P03 | Rotate/background during save/import/export; repeat after activity/process death on an emulator. Failed/lost export must not truncate an existing destination. Review saved-state/logs for credentials using a synthetic secret marker. Device-protected/credential-protected storage and real Keystore failures are distinct tests. |
| P04 | SSH first-use prompt: Cancel, verify fingerprint independently, Accept and reconnect. Change the server host key, confirm rejection; test jump and destination separately, including password/key modes and different credentials. No silent trust change or direct fallback. |
| P05 | HTTPS and, where included, MASQUE α: valid identity, untrusted/self-signed and wrong-host certificates; explicitly allow an exception only for its selected hop and restore verification. Check malformed custom JA3/profile selection. An allowed self-signed fixture is not positive evidence of CA/hostname verification. |
| P06 | With a profile/global-settings editor open, refresh its subscription with changed credentials/routing; save only a name or one setting. Untouched fresh values and unreadable encrypted secrets must survive; an intentional edit wins only for that field. Repeat a failed write/retry and profile deletion during save. Also change SSH host/port/pin while its trust prompt is open: accepting the old prompt must not trust the changed endpoint. Inspect credential screens and recent-app snapshots for FLAG_SECURE protection. |
| P07 | On disposable emulator data, inject malformed profile/global JSON, wrong routing-field types and unavailable Keystore secrets. Preserve original stored bytes and show a usable error; never silently reset routing or credentials. Startup must refuse unsafe defaults; Stop remains available for a running manual VPN, respecting Always-on. Trigger failover with damaged settings: no crash or silent profile switch. Restore the fixture explicitly, then verify recovery. Test legacy IPv6 migration against the configuration actually used by the first connection. |

### Real forwarding, routing and diagnostics

| ID | Steps → required observation |
| --- | --- |
| T01 | Ordinary-app HTTP and HTTPS requests through HTTPS/GOST, SSH/OpenSSH, HTTPS Jump, SSH Jump, SOCKS5 and MASQUE α; exercise both selections of HTTPS HTTP/3 preference (H12–H15). Use unique tokens, binary upload/download comparisons and server-side evidence. Exercise multiple requests and simultaneous streams; confirm destination/jump roles and no double traffic counting. Use an origin that replies only after the client write half-close; the full response must survive FIN on every transport. |
| T02 | Correct → wrong → missing → restored credentials at each authenticated hop. Require working controls and no successful origin delivery for the negative token. A “Connected” label with rejected requests is not success. |
| T03 | Proxy refused, timeout, reset, unreachable origin and truncated response; restore each. Application must report bounded errors and recover. No direct fallback unless the selected routing policy explicitly calls for it. |
| T04 | All-app routing on an emulator; selected-app routing on every device with two ordinary UIDs (included/excluded). Toggle local-network bypass and probe controlled LAN/private and public targets. Confirm included/excluded decisions from observed route, not just checkboxes. |
| T05 | IPv4 and, with a verified IPv6 uplink/origin, IPv6 allow/block. HTTPS over TCP (HTTP/1.1 or HTTP/2) and SSH must block proxied non-DNS UDP/QUIC; test configured local bypass separately; blocked tokens must not reach the UDP origin with a working direct control. For MASQUE α, HTTPS selecting HTTP/3 and SOCKS5, verify tunneled UDP with origin evidence, local UDP bypass and oversized datagram failure; test Chrome/Firefox constraints and GOST IPv6-target limitations separately. A browser falling back to HTTP/2 is not QUIC support. See [MASQUE limits](masque.md). |
| T06 | Run connection diagnostics: exit IP/country, primary failure and fallback. Compare with controlled server egress. Check cancellation and invalid/offline profile behaviour; inspect logs for synthetic credential leakage. Distinguish failed external providers from broken tunnelling. |
| T07 | Transfer a known amount, inspect upload/download totals, reset/limit behaviour and persistence. The SSH session rotation threshold in MiB must rotate the session as documented; it is not a traffic quota and must not be tested as a VPN cutoff. Test selected/all failover with unavailable primary, healthy secondary and exhausted candidates; verify actual egress and Stop during failover. |
| T08 | Check [TCP metrics](connection-metrics.md) with controlled traffic: RTT is to the first proxy, not website latency; no data differs from zero. Induce loss on a dedicated fixture, correlate outgoing retransmits with independent capture, and verify expiry after five minutes without new readings. Check jump/multiplexed sockets are counted once, standalone diagnostics do not change these metrics, and a new VPN session resets them. Record native-test evidence separately from device observations. |
| T09 | Inspect successful HTTPS and SSH negotiation logs (plus MASQUE α HTTP/3 multiplexing and selected fingerprint where included) against the controlled server: TLS version/cipher/ALPN, actual HTTP CONNECT version after fallback, and initial SSH algorithms in both directions. Exercise both jump hops. Verify events contain no IPs, domains, credentials, certificate identities or raw banners; use synthetic markers. RTT/retransmits alone must not trigger failover; timeout/reset recovery remains a separate check. |
| T10 | Run Test with MASQUE, SOCKS5 and HTTPS selecting HTTP/3: retain separate Cloudflare and BrowserLeaks HTTP/3 verdicts. One/both unavailable must not erase a successful HTTPS/exit-IP result. In HTTPS fallback show the UDP warning, without false HTTP/3 success. These native probes use proxied UDP without TCP fallback but bypass Android TUN; additionally use an included ordinary browser/app and controlled HTTP/3 origin (H06). External-service availability is supporting evidence, not a deterministic CI gate. During a delayed temporary Test, Stop/start or switch profile: old completion must neither stop the replacement VPN nor publish stale success/diagnostics. |

### Configuration subscriptions

Use disposable HTTPS feeds and synthetic credentials. Record C01–C05 separately;
manual refresh and scheduled delivery are distinct subcases.

| ID | Steps → required observation |
| --- | --- |
| C01 | Bootstrap a subscription; pause it and refresh manually; restart with an overdue check. Verify primary failure → valid backup → recovered primary and all sources unavailable. No bootstrap starts VPN. Ownership, local profiles and surviving active/Always-on selections remain correct when remote profiles disappear. |
| C02 | Reject 401/403, redirects, invalid UTF-8, oversized decoded gzip, malformed fields, empty snapshots and canonical ID collisions without replacing working profiles. Change/remove the subscription during download: obsolete results cannot commit or clear a newer refresh's busy state. Remote subscription definitions cannot replace local source URLs/recipients. Check encrypted storage, redacted exports and Keystore process restart. |
| C03 | Change an active endpoint/credential/routing policy in background while connected and while startup is delayed. Keep existing traffic until explicit reconnect; after startup, still flag changes that missed the original snapshot. Show a private reconnect notification and in-app indicator. Name-only/inactive-profile changes do not demand reconnect. Deny notification permission/disable channel: retain the in-app action and usable VPN. |
| C04 | Deliver two successive updates and use current versus stale reconnect actions; then Stop and invoke an old action. Only the current token on a running, desired VPN can reconnect. Applying an earlier snapshot must not clear a newer pending change. Verify actual new egress/credentials, cancellation of obsolete notification and no resurrection after Stop. |
| C05 | Refresh while leaving/recreating the subscription screen and while another editor has pending saves (P06). Completion clears busy state; writes finish or expose failure/Retry without losing unrelated edits. Inspect notification, logs and task snapshots for synthetic URL/password/profile markers; no feed payloads or credentials may appear. |

### SOCKS5

Repeat shared profile, routing, DNS, lifecycle, update and diagnostics cases with
SOCKS5. Use a disposable GOST fixture with UDP enabled and a reachable relay.

| ID | Steps → required observation |
| --- | --- |
| S01 | Anonymous and authenticated TCP CONNECT: correct/wrong/missing credentials, restored control, Stop/restart. Reject only-one-credential and values exceeding 255 UTF-8 bytes; include multibyte boundaries. Round-trip JSON and socks5:// with/without secrets. No SOCKS5 Jump or browser TLS fingerprint is promised; UI/import must not silently change transport. SOCKS5 credentials are plaintext on the proxy link, so use only disposable secrets. |
| S02 | UDP ASSOCIATE: binary 1200/1350/4096-byte echoes, several destinations, UDP-disabled server and unreachable/invalid relay. Wildcard relay uses the proxy bootstrap IP; a hostname relay must fail without system-DNS lookup. Close the TCP control connection: old UDP association terminates and a fresh connection recovers. Combine with per-app routing, bypass, IPv6 and D05. |
| S03 | Check TCP/UDP totals without counting relay framing twice; TCP RTT/retransmits concern TCP/control sockets, not UDP latency/loss. Test sustained UDP and Stop under load; use the native allocation-budget regression as separate evidence that reads no longer allocate 64 KiB per datagram. Device CPU/memory observations need their own duration/load baseline. |
| S04 | Fail over to/from SOCKS5, HTTPS/SSH and MASQUE; verify new TCP/UDP origin tokens, current badge and credentials, no direct fallback. Failed or unsupported UDP alone does not establish proxy blocking; HTTP/3 availability is a separate T10 result. |

### MASQUE α / HTTP/3

Run H01–H11 for explicit MASQUE and H12–H15 for HTTPS HTTP/3 preference,
including Jump, on all four devices. Also repeat
I01–I06, P01–P03, P06–P07, T02–T04, T06–T10, C01–C05, L01–L11, D01–D05 and U02–U04 with a
MASQUE profile: the transport cases do not replace shared feature checks. For a
candidate lacking a mode, record only its cases as N/A with the artifact identity.

Use a disposable authenticated GOST fixture from the [MASQUE guide](masque.md),
record its version/configuration, and provide controlled TCP, UDP echo and HTTP/3
origins. Keep a trusted, hostname-matching certificate for positive trust tests;
use separate untrusted/wrong-host fixtures for negative controls. Verify UDP
reachability from the device and emulator host; TCP/443 or ADB reverse alone is
insufficient. Retain private server logs/captures with request tokens. Distinguish
outer HTTP/3 to the proxy from an app's inner QUIC traffic to an origin.

The API 26/API 35 runner also exercises Randomized/custom JA3, rejected and corrected
credentials, rejected self-signed certificates with an explicit exception control,
Chrome/Firefox oversized UDP followed by small packets on the same socket, real
per-app routing, and timeout-driven MASQUE → custom HTTPS failover. It also checks anonymous/authenticated
SOCKS5 TCP/UDP, HTTPS preference and all four Jump availability pairs. Positive
private-origin TCP requests precede one-shot UDP/TCP DNS AAAA-suppression probes,
so VPN route readiness is proven before sending UDP; these do not prove real DoH
resolution or leak resistance. It runs against
a disposable authenticated GOST fixture and private origin. This suite does not
replace the trusted-certificate, DNS-capture, inner HTTP/3, IPv6-uplink or physical
handover checks below.

| ID | Steps → required observation |
| --- | --- |
| H01 | Select MASQUE α, save host/UDP port/credentials and each fingerprint; restart, clone, export/import JSON and `masque://` ProxyList, with/without secrets (P01–P03). JSON preserves `proxy.type: "MASQUE"`, port and settings; ProxyList preserves its supported endpoint/credential fields, not global fingerprint settings. Verify real connectivity after import. Check EN/RU α label and default certificate verification. Profile Jump types remain HTTPS/SSH; HTTPS Jump can prefer HTTP/3 on both hops (H13). Unsupported imported combinations must be rejected without silently changing transport or routing. |
| H02 | Send binary TCP upload/download and simultaneous requests to two origins, plus a UDP echo flow. Correlate origin tokens with GOST connection/stream records: one shared outer QUIC session carries independent CONNECT/CONNECT-UDP streams. Close one flow while others continue. Confirm HTTP/3 and multiplexing diagnostics; a badge or log alone does not prove traffic or session reuse. Missing server datagram/extended-CONNECT settings or CONNECT-UDP Capsule-Protocol must produce a clear failure. |
| H03 | Correct → wrong username → wrong password → missing credentials → restored credentials, for both TCP CONNECT and CONNECT-UDP. Negative tokens must not reach the origins; record GOST rejection and bounded client failure. An established QUIC handshake is not successful authorization. Reconnect after editing credentials and check that the prior authenticated session is not reused. |
| H04 | Trusted matching certificate succeeds with verification enabled; untrusted/self-signed, wrong-host and expired certificates fail without origin delivery. Explicitly permit only the selected fixture, reconnect, restore verification and repeat rejection. Confirm an unrelated profile without an explicit exception still verifies certificates. Record handshake evidence separately from HTTP authentication. |
| H05 | Chrome Android, Firefox Android, Randomized and valid Custom JA3 (QUIC JA3 in the MASQUE profile, TLS JA3 in Settings): reconnect for each, exercise TCP and a small UDP payload, and inspect captured ClientHello/QUIC transport parameters against the selected preset/custom fields. Malformed or QUIC-incompatible JA3 must fail clearly. Compare Randomized across fresh sessions. Record the uQUIC preset versions and ALPN `h3`; selected-profile logs are not wire evidence, and preset matching does not establish an exact whole-browser HTTP/3 fingerprint. |
| H06 | UDP echo with byte/address comparison at 512 bytes, Chrome at 1200 bytes, and around the negotiated datagram/path-MTU boundary; then try an ordinary app's real QUIC request to the controlled HTTP/3 origin. Confirm GOST egress and actual origin HTTP/3, with HTTP/2 fallback disabled for this probe. Oversized packets must be dropped whole with a diagnostic, without truncation, direct fallback or breaking a subsequent small packet/TCP stream. Record Firefox's inability to carry a 1200-byte inner QUIC Initial and GOST's missing reliable capsule fallback as limits; small UDP success is not full QUIC success. |
| H07 | Repeat selected/all-app routing and local bypass using included/excluded ordinary UIDs, public/LAN TCP and UDP origins, and verified IPv4/IPv6 controls (T04–T05). IPv4-only must block IPv6 TCP and UDP; with IPv6 enabled verify TCP and local UDP bypass. Record GOST 3.3.0 IPv6-literal CONNECT-UDP rejection separately as a blocked tunneled-UDP subcase, not PASS for IPv6 UDP or an acceptable direct fallback. |
| H08 | Fresh names through the configured DoH, primary failure → fallback → all failed (D01–D02). Correlate DoH over the MASQUE TCP tunnel and external DNS capture: UDP and TCP port 53 (D05) must retain DoH handling rather than become arbitrary CONNECT-UDP or unintended plaintext DNS. Exercise Private DNS/lockdown controls separately; absent external capture leaves leak resistance NOT TESTED. |
| H09 | Compare known TCP/UDP payload totals and rates, IEC/SI, reset/persistence, Test exit IP/country and provider fallback (T06–T08). Verify HTTP/3 badge and multiplexing events, and clear stale status after Stop/profile switch. For MASQUE, verify QUIC RTT and outgoing packet-loss declarations over five minutes, including zero versus unavailable, expiry, session reset and shared-session counting; QUIC must not fabricate kernel TCP RTT/retransmit values. Inspect diagnostics with synthetic markers: no credentials, target identities, host-key pins or certificate details; verify TLS/cipher/ALPN, numeric QUIC errors and correlated local stream/session numbers, bounded duplicate events and no false DPI failures on Stop; private packet captures are separate evidence. |
| H10 | Block the proxy UDP port while TCP/443 remains reachable, restart GOST, force offline/online, and repeat real Wi-Fi/mobile handover (L01–L08). Confirm bounded failures and fresh working TCP/UDP flows after recovery; existing streams may terminate. Stop during handshake/reconnect must prevent late connection and traffic. Explicit MASQUE has no implicit HTTPS or direct fallback (HTTPS preference is H12–H15); virtual cellular alone does not verify carrier handover, and recovery alone does not prove QUIC connection migration. |
| H11 | Selected/all-profile failover with unavailable MASQUE primary, healthy secondary, exhausted candidates and Stop during switching (T07). Exercise both MASQUE→HTTPS/SSH and HTTPS/SSH→MASQUE, including separate valid custom TLS/QUIC JA3 values; correlate TCP egress and new UDP tokens. UDP follows the active transport: MASQUE tunnels supported payloads, HTTPS over TCP and SSH block proxied non-DNS UDP; SOCKS5 and HTTPS selecting HTTP/3 relay supported UDP too. No stale badge/session/credentials, unintended direct delivery or double counting after switching. |
| H12 | HTTPS: preference off uses TCP; preference on selects HTTP/3 on the same host/numeric UDP port when available. Block UDP while TCP stays reachable: the optional probe is bounded to three seconds, then HTTP/2 or HTTP/1.1 works with a visible UDP-fallback warning and DoH retained. The probe alone must not produce a blocking warning/failover. Reconnect repeats selection; established HTTP/3 streams are never silently moved to TCP. Persist the preference through JSON/clone/restart; plain proxy URLs omit it. |
| H13 | HTTPS Jump: test H3/H3, H3/TCP-only, TCP-only/H3 and TCP-only/TCP-only hops. Only the first pair uses nested HTTP/3; otherwise the whole chain falls back to HTTPS through the jump, with no direct exit connection. Verify separate credentials/certificate policies, remote exit-host resolution, real TCP and 1200/1280/1350-byte UDP, asymmetric MTU/loss and Stop during exit handshake. Capture both hops; one successful outer handshake proves neither exit authorization nor usable inner QUIC. |
| H14 | For each applicable hop, contrast unavailable protocol/settings, CONNECT-UDP 404/405/501 and missing Capsule-Protocol (eligible fallback) with bad credentials, certificate failures, access denial and unexpected server failures (terminal, no downgrade). Edge/QUIC-incompatible custom fingerprint selects HTTPS; Firefox Jump falls back for its datagram limit. Socket-protection/unknown-error cases require native fault-injection evidence. Explicit MASQUE still fails instead of falling back. |
| H15 | During active QUIC traffic induce lost ACKs/retransmission, GOAWAY and local request deadlines. New streams recover while unrelated healthy streams remain usable where the protocol allows. Record the native uQUIC retransmission regression separately; a successful handshake does not exercise it. One failed shared QUIC session must not count each failed stream as an independent blocking incident; test independent failures and subsequent successful tunnel recovery (L10). |

Record verdicts separately for each fingerprint, TCP/UDP, payload size and address
family. The existing `native_integration` and API 26/API 35 device scenarios are
supporting evidence, not coverage of every H case: Chrome TCP/1200-byte UDP and
Firefox TCP/512-byte UDP checks do not establish trusted-certificate validation,
wire fingerprint fidelity, real handover or end-to-end browser QUIC. Known alpha
limits must remain visible in the release decision; do not mark unexecuted cases PASS.

### Lifecycle, network changes and background operation

| ID | Steps → required observation |
| --- | --- |
| L01 | Home/lock screen/task removal and foreground return during real traffic. Record notification, VPN state, process identity and server responses. Check notification actions open the correct screen. |
| L02 | Interrupt the proxy and restore it while connected. Run at least two cycles, including Stop during reconnect. Confirm recovery without manual restart and no late reconnect after explicit Stop. |
| L03 | Wi-Fi → mobile → Wi-Fi and fully offline → online, two cycles with ordinary-app requests. Record actual underlying Android network and server source address. Emulator virtual cellular and an ADB-reverse tunnel do not establish carrier handover. |
| L04 | Kill the background process on a dedicated emulator, distinguishing process death from `am force-stop`; record ApplicationExitInfo/logcat and restart behaviour. Force-stop must not be described as an OS crash. Real LMKD/OOM pressure is a separate bounded scenario, not proven by killing a PID. |
| L05 | Configure Always-on and lockdown, reboot with server available/unavailable, unlock and restore server. Confirm saved profile, system VPN settings, blocked ordinary-app traffic during outage and successful recovery. Request manual unlock without recording the user's PIN. |
| L06 | Install an update while VPN is connected; check package-replaced recovery and real traffic. Repeat with connection explicitly stopped: package replacement must not enable it. Preserve signatures and APK variant; do not publish fixture versions. |
| L07 | Dedicated emulator Direct Boot: set a temporary test PIN, reboot, independently verify `RUNNING_LOCKED` before/after ordinary-UID direct-boot probes; then unlock and retest. A black screen or a post-unlock observation is not this case. Record whether MegaProxy promises pre-unlock operation. |
| L08 | Forced Doze with recorded screen-off/IDLE state, app exemption state and ordinary-app probes; outage/recovery while idle, then unforce/reset battery and retest. Include a timed foreground/background soak (at least 15 minutes) and record duration. Shell UID is not an ordinary background-app control. Long-term battery/24-hour behaviour remains separate. |
| L09 | Stop while HTTP/1 CONNECT is stalled, SSH/QUIC admission queues are full and TCP/UDP local-bypass or DNS flows are open. Old sockets and waiters terminate promptly; old server-side flows cannot carry new tokens. Repeat rapid Stop/start/profile switch and delayed callbacks: the new VPN, protocol, warning and profile remain current. Post-Stop direct traffic allowed by Android policy is distinct from a leaked old tunnel; use connection-specific server evidence and separate lockdown controls. |
| L10 | Seed a stale proxy/jump bootstrap IP, change its DNS answer and induce refused/unreachable: a bounded fresh resolution can recover without calling refusal proof of blocking; late old failures must not discard a newer address. Induce actual proxy failures within/outside the 60-second evidence window, then a successful tunnel: stale blocking warnings clear without erasing unrelated warnings. A target reset, local deadline, local bypass failure or duplicate shared-session error must not trigger global failover. Include a timed healthy soak; report a long-duration test separately from the 15-minute minimum. |
| L11 | Failover candidates include blank drafts, incompatible transport/fingerprint settings and unreadable credentials plus a valid alternative: skip invalid effective configurations, including global settings, and connect only to an allowed valid candidate. Repeat selected/all/exhausted modes. For SSH and SSH Jump, silence keepalive replies: the ten-second reply deadline retires the dead session and new requests can recover; Stop interrupts waiting. Also verify bidirectional/half-close replies and configured channel limits/session rotation under load. |

### DNS, lockdown and external control

| ID | Steps → required observation |
| --- | --- |
| D01 | With active VPN, resolve fresh names via Java InetAddress, native getaddrinfo and Android DnsResolver where supported. Correlate DoH requests/proxy traffic and independent DNS capture. Verify primary DoH failure, configured fallback and all-upstreams-failed: no unintended plaintext fallback. Cached answers and failed browser loading do not establish DNS routing. |
| D02 | Direct control → active VPN → unavailable proxy → force-stop with lockdown → restart → direct control. Repeat fresh names twice using ordinary apps and browsers where safe. Capture IPv4/IPv6 UDP/TCP 53 and strict Private DNS/853 where available. No second capture VPN: it would replace the subject. If external capture is unavailable, leak resistance is NOT TESTED. |
| D03 | Compare known Android/OEM limitations against current primary sources. Separate observed leakage from attribution to MegaProxy, shell-only effects, browser Secure DNS and fixture failures. Record exact OS build; do not extrapolate to every Android release. |
| D04 | Use an ordinary test app to attempt exported intents/broadcasts/provider access; private receivers and trust/export screens must not expose secrets or change VPN/trust state. Inspect manifest protection and runtime refusal. ADB shell permissions alone are not this evidence. |
| D05 | For every transport and HTTPS preference outcome, send explicit UDP/53 and framed TCP/53 queries from an ordinary UID, including LAN destinations with bypass enabled. Both must use configured DoH with fallback/IPv6 policy; no plaintext TCP retry escapes. Exercise fragmented TCP prefix/payload, pipelined IDs, write half-close with pending replies, timeout, malformed length and Stop. Check primary/fallback/all failed and AAAA suppression; CUSTOM and filtering providers must not silently switch to unfiltered resolvers. Separate these intercepted queries from Android Private DNS/browser Secure DNS, which follow normal routing. Native framing/DoH fixtures support but do not replace packet-capture evidence. |

### Updating and distribution

| ID | Steps → required observation |
| --- | --- |
| U01 | Official and alternative F-Droid installers (fdroidrepo/fdroidrepos handlers), browser/file installer, unknown installer and manual override. An unrelated installed F-Droid client must not change a browser installation's source. Check chosen API and persistence. An unpublished F-Droid package gives an explicit unavailable result without switching to GitHub. Simulated installer identity is not installation from the repository. |
| U02 | New/same/older/malformed release metadata and unavailable network. Explicit download consent/cancel, APK size/digest/package/version/signer/ABI checks, corruption and download interruption. Use unit tests for deliberately invalid APKs and isolate destructive fixtures to emulators. After repeated downloads, retain only the current APK and one predecessor for an active installer; stale .part files older than two days are pruned without deleting a fresh download. Use controlled timestamps for cache tests and verify the installer can still read its granted URI. |
| U03 | Universal stays universal, ABI-specific stays that ABI; system install permission denied/granted, cancellation, return from settings and successful update. Read back installed APK/version/signature and verify real connectivity afterward. |
| U04 | Notifications allowed/denied/channel disabled; Update, Skip version, Disable checks; manual check after skip; disable while job is in flight; job restoration after reboot. Test no early reminder and reminder after seven days by controlled timestamp injection on an emulator. Report forced jobs/time simulation separately from real daily/weekly delivery. |

### Exit review and restoration

| ID | Steps → required observation |
| --- | --- |
| E01 | Collect app-scoped Java/native crashes, ANRs, exit reasons and diagnostic errors across process restarts. Correlate timestamps/actions and reproduce findings. Absence from a partial log is not proof of no crashes. |
| E02 | Stop fixtures/captures, remove only created forwards/reverses, probe apps/files and test profiles; restore original routing, active/Always-on profile, permissions, network, Private DNS, screen/font/language and battery settings. Preserve original app/data and pre-existing VPN state. Verify restoration on each device; list any residual state. |
| E03 | Recheck candidate commit and artifact hashes, complete all matrix cells and link evidence. Summarize failures and limitations by feature, not setup chronology. Keep raw private artifacts out of the PR and verify the staged file allowlist. |

## Keeping the procedure current

Before selecting cases, record the last commit touching each language version and
review every subsequent candidate commit (`git log BASE..CANDIDATE` and the diff),
not only release notes. Map each product change/fix to case IDs and existing
JVM/Go/device tests; keep gaps explicit. Also compare the complete current feature
inventory: a feature can predate BASE yet still be missing from the procedure.
Record automated evidence and signed-device verdicts separately, including skipped
suites and fixture limits. Preserve existing case IDs and dated historical reports;
update both language versions together. The automatic release workflow checks CI
and publishes after tagging; it does not collect or enforce this manual matrix.

## Findings, fixes and release decision

Reproduce a finding on the unchanged candidate and keep that evidence. Record
severity, affected devices, precise reproduction, actual versus expected result,
controls, suspected layer and confidence. Do not call a platform defect an app
defect without evidence. Inspect a dangerous source path even if runtime did not
trigger it; label a source finding as such.

Keep the procedure/report PR separate from fixes. Start a fix branch from current
main (or use an isolated worktree), add a regression test that fails before the
fix, then apply the smallest root-cause correction. Put parsers/state/formatting
in JVM tests, screen interactions in existing Robolectric tests, native transport
logic in Go tests, real server interoperability in `native_integration`, and
platform lifecycle/routing in the existing API 26/35 emulator CI and local device
evidence. Keep hosted checks deterministic; physical-network, OEM and long-soak
coverage remains local. Retest the reproducer and adjacent paths on affected devices;
identify the new commit/APK explicitly. Original FAIL does not become PASS without
an executed retest.

Do not recommend release with unexplained crashes, data loss, trust/authentication
bypass, wrong APK/signer/ABI, failed basic forwarding on a supported device, or
unverified required migration/lockdown behaviour. A blocked critical gate means
**RELEASE NOT VERIFIED**, not “all good”. State known platform limitations and
remaining coverage explicitly; publication remains a separate user action.

Suggested report: `docs/reviews/release-YYYY-MM-DD.md`, with candidate/artifact
table, four-device inventory, case matrix, feature findings, retest references,
release decision and private evidence location. Historical reports stay dated;
update this procedure when product behaviour or the test method changes.

[Русская версия](../ru/release-testing.md)
