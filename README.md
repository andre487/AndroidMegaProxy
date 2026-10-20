# MegaProxy

[![CI](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)
[![Release](https://img.shields.io/github/v/release/andre487/AndroidMegaProxy)](https://github.com/andre487/AndroidMegaProxy/releases/latest)
[![VirusTotal: latest report](https://img.shields.io/badge/VirusTotal-latest%20report-blue?logo=virustotal&logoColor=white)](https://github.com/andre487/AndroidMegaProxy/releases/latest#virustotal)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)

<p align="center">
  <img src="assets/branding/megaproxy-launcher-master.png" width="160" alt="MegaProxy app icon">
</p>

MegaProxy is an open-source Android VPN client for reliable, secure connections through proxy
servers you control or trust. It supports HTTPS, SOCKS5, MASQUE α (HTTP/3) and SSH transports, per-app routing, encrypted DNS,
connection diagnostics, and automatic failover in one privacy-focused application.

MegaProxy contains no advertising, analytics SDKs, tracking, or remote telemetry. Connection
statistics and diagnostic logs stay on the device unless you explicitly choose to share them.

> MegaProxy is under active development. Review the current limitations before relying on it for
> critical connectivity.

## Features

Proxy bootstrap addresses are cached for up to seven days. Refused or unreachable proxy
connections discard a matching cached address and retry DNS once per profile recovery period,
without treating the failure alone as evidence of blocking. SSH keepalive replies have a ten-second
deadline; a silent session is closed so subsequent connections can reconnect. SSH key approval
is bound to the captured server hostname, port and previous pin, so a changed profile must be reviewed again.

### SOCKS5

Select **SOCKS5** and enter the server hostname and port (default 1080). Leave both
credentials empty, or enter a username and password of 1–255 UTF-8 bytes each.
SOCKS5 does not encrypt the proxy connection or credentials and does not apply a
browser TLS fingerprint. Application TLS and DoH retain their own encryption.
DNS uses the selected DoH provider over proxied TCP; other UDP uses UDP ASSOCIATE.
The relay must return a reachable IP address and nonzero UDP port. Wildcard relay
addresses use the proxy bootstrap IP; hostname relays are rejected without system DNS.
Local-network bypass, app routing, IPv6 policy, failover and traffic counters apply.
TCP RTT/retransmits describe the first proxy's TCP sockets, including UDP control
connections; these are not measurements of the relayed UDP path. No SOCKS5 Jump
mode is defined. JSON uses `proxy.type: "SOCKS5"`; ProxyList accepts
`socks5://host:1080` and `socks5://user:password@host:1080`.

For GOST, enable UDP explicitly: `gost -L 'socks5://user:password@:1080?udp=true'`.
See [GOST SOCKS5 documentation](https://gost.run/en/tutorials/protocols/socks/).
The proxy's advertised UDP relay must be reachable through its firewall/NAT.

### Connection profiles

- Multiple named, colored, reorderable profiles.
- HTTPS proxies over TLS with Basic authentication, including two-proxy HTTPS with Jump chains.
- MASQUE α over HTTP/3 with Basic authentication, multiplexed TCP CONNECT and CONNECT-UDP.
- HTTP/2 CONNECT multiplexing when supported by the proxy, with automatic HTTP/1.1 fallback.
- SOCKS5 TCP CONNECT and UDP ASSOCIATE, with optional RFC 1929 username/password authentication.
- SSH `direct-tcpip` transport and SSH through a jump host.
- SSH password and unencrypted private-key authentication.
- SSH host-key verification with trust-on-first-use confirmation.
- Profile cloning, import, export, and configurable failover.
- Import from MegaProxy JSON, ProxyList, FoxyProxy JSON, and supported SuperProxy text files.

### VPN and routing

- Global VPN and per-app split tunneling.
- Local-network bypass enabled by default.
- Per-profile IPv6 support; IPv4-only operation is the default.
- Android Always-on VPN integration and a persistent foreground-service notification.
- Pending routing/profile changes offer reconnect; Android network recovery and configured failover are automatic.
- Approximate upload/download speed, kernel TCP RTT to the first proxy, and outgoing TCP retransmits observed over the last five minutes.
- Session traffic totals with selectable IEC/SI units, connection start time and elapsed duration.

### DNS and transport

- UDP/TCP port 53 DNS interception with DNS-over-HTTPS through the configured transport.
- Cloudflare, Google, Quad9, Yandex Basic, Yandex Safe, Yandex Family, and custom DoH endpoints.
- DNS-provider fallback where it does not weaken an explicitly selected filtering policy.
- HTTPS ClientHello profiles powered by uTLS, MASQUE TLS/QUIC presets powered by uQUIC,
  plus manual JA3 configuration.
- Configurable SSH client profiles, keepalives, channel limits, and session rotation.
- MASQUE and SOCKS5 forward UDP; HTTPS and SSH block arbitrary UDP so QUIC clients fall back to TCP.

### Diagnostics

- A staged connection test for proxy setup, `example.com`, and the observed exit IP and country;
  MASQUE and SOCKS5 also check end-to-end HTTP/3 over proxied UDP with Cloudflare and BrowserLeaks.
- Local, size-limited, rotating diagnostic and crash logs designed to omit credentials and traffic
  content.
- [Negotiated TLS/HTTP/SSH parameters](docs/en/connection-metrics.md#negotiation-diagnostics) without peer identities or credentials.
- **VPN visibility** compares local signals and cached ProxyCheck IP reputation per route.
- **Speed** compares download/upload, latency, exit IP/country and negotiated HTTP/TLS over the underlying network and VPN.
- Optional feedback or crash reports opened in the user's email client; nothing is submitted
  automatically.
- English and Russian interfaces with an in-app language selector.

## Privacy and security

MegaProxy does not operate a proxy service and does not send configuration or usage data to the
project author. Network traffic is sent only where required by the selected profile, destination,
and DNS configuration. Proxy-hostname bootstrap may contact Cloudflare, Yandex, Google or Quad9
DoH resolvers directly before the tunnel exists. The explicit connection test contacts `example.com`
and first obtains exit IP and country together from `speed.cloudflare.com/cdn-cgi/trace`
through the proxy. Missing or invalid fields independently fall back to IP providers
(`ifconfig.me`, `api.ipify.org`, `icanhazip.com`) and country providers
(`ifconfig.co`, `ipapi.co`, `api.country.is`). With MASQUE or SOCKS5 it also sends
HTTP/3 requests to `www.cloudflare.com/cdn-cgi/trace` and `quic.browserleaks.com/` through
CONNECT-UDP, with verified destination certificates and no TCP fallback. Both providers are
checked independently; an unavailable provider does not invalidate the HTTPS/IP result. These
native probes verify the proxy UDP path, not Android TUN or per-app routing, and their inner
QUIC fingerprint is the diagnostic client's, not the selected outer MASQUE fingerprint or
Chrome's. The VPN visibility screen obtains each route's public IP through the connection test's
provider sequence
(Cloudflare trace first, then `ifconfig.me`, `api.ipify.org`, and `icanhazip.com`), then queries `https://proxycheck.io/v3/`
and reports its VPN, proxy, Tor, or anonymous-IP verdict. Quotas are controlled by ProxyCheck; MegaProxy reports an exhausted daily allowance separately from request
failures and caches successful verdicts for each public IP for 24 hours.
An explicitly started speed test requests route metadata from
`https://h3.speed.cloudflare.com/cdn-cgi/trace`, measures median time to first byte with three empty
downloads, and transfers a user-selected 10, 25 or 50 decimal megabytes (10,000,000 / 25,000,000 / 50,000,000 bytes) per direction and available route through
`https://h3.speed.cloudflare.com/__down` and `https://h3.speed.cloudflare.com/__up`.
This protocol-capable hostname is required: the ordinary `speed.cloudflare.com` hostname may
negotiate only HTTP/1.1.
The Go client binds DNS and TCP/UDP sockets to the selected Android network. Users select h3
(default, falling back to h2 then h1), h2 (falling back to h1), or h1. Only the initial
metadata probe permits protocol fallback; certificate failures, HTTP service errors, and transfer
failures do not restart the payload. The screen log includes DNS/TCP/TLS/QUIC timings, HTTP status,
connection reuse, latency samples, byte counts, transfer rates, and protocol fallback reasons.
It displays each result as it arrives, with selectable exit IPs, country flag/code and separate
HTTP/TLS lines. Leaving the speed screen cancels the measurement; backgrounding the app does not.
Responses and fingerprint data are not stored in diagnostic logs. See [PRIVACY.md](PRIVACY.md).

- HTTPS and MASQUE proxy certificates are checked against the Android trust store, including hostname and
  validity. Normal CA certificate renewal does not require certificate pinning.
- UDP and TCP port 53 queries received by the VPN are converted to DoH. Private DNS
  and browser Secure DNS follow normal traffic/routing rules; they are not rewritten
  to the selected DoH provider.
- Application TLS remains between the application and its destination. MegaProxy does not install
  a CA certificate and does not perform TLS interception.
- Proxy passwords and imported private keys are encrypted with AES-GCM using a key held by Android
  Keystore.
- Android cloud backup and device-to-device transfer are disabled for application data.
- Every upstream socket is protected from recursive routing through the VPN.
- SSH host keys are verified and unknown keys require explicit user confirmation.

Two compatibility options deliberately reduce these protections: accepting an invalid HTTPS or MASQUE
proxy certificate and accepting any SSH host key. MegaProxy displays a warning before enabling
them. Use either option only when you understand and control the associated risk.

The proxy or SSH server can observe connection metadata and the destinations it is asked to reach,
even though it cannot read end-to-end encrypted application content. The operator of that server
must therefore be trusted. No application can override an explicit Android force-stop, and device
vendors may impose additional background-execution restrictions.

## Installation

MegaProxy requires Android 8.0 (API 26) or newer and your own server configuration.

<table>
  <tr><th align="center">GitHub</th><th align="center">F-Droid</th></tr>
  <tr>
    <td align="center"><a href="https://github.com/andre487/AndroidMegaProxy/releases/latest/download/mega-proxy-universal.apk"><img src="docs/assets/get-it-on-github.png" alt="Download APK from GitHub" height="75"></a></td>
    <td align="center"><a href="https://f-droid.org/packages/net.megaproxy487/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="75"></a></td>
  </tr>
</table>

Detailed instructions cover choosing an APK, installation permissions, checksums, ADB,
updates without losing settings, and troubleshooting:
**[English](docs/en/installation.md)** / **[Русский](docs/ru/installation.md)**.
PR workflow artifacts are test builds; use release assets for everyday use.

**Settings → App updates** checks the installer-selected source (F-Droid or GitHub), with daily
background checks enabled by default, weekly reminders, and explicit consent before APK downloads.
GitHub updates preserve the installed APK variant. Full behavior and controls:
[English](docs/en/updates.md) / [Русский](docs/ru/updates.md).

After installation:

1. Create or import a connection profile.
2. Choose global routing or select applications for split tunneling.
3. Review DNS and fingerprint settings if the defaults are not appropriate for your server.
4. Open the main-screen menu and choose **Test**, then tap **Connect**.
5. Optionally enable Always-on VPN in Android settings.

Server configurations and setup instructions are maintained separately in
[MegaProxyServer](https://github.com/andre487/MegaProxyServer).

### Generated configuration imports

External generators can produce MegaProxy JSON files using schema `net.megaproxy487.config`, version
8. Every profile must have a stable, generator-controlled `id`. Reimporting a file updates profiles
with matching IDs and adds only new IDs; it does not create duplicates. Omitted password and SSH
private-key fields preserve credentials already stored on the device, while explicit empty values
clear them. After import, MegaProxy offers an unselected list of local profiles absent from the file
so the user can optionally remove specific obsolete profiles.

The portable contract is maintained in [MegaProxyConfig](https://github.com/andre487/MegaProxyConfig).
Pinned schemas and examples live in `config-schema/`; update them explicitly with
`bundle exec fastlane android renew_config_schema` (or `ref:FULL_SHA`). JVM checks validate real
exports against both the Android baseline and shared schema. Import results report ignored browser
settings and fields unknown to the pinned specification once each, without displaying values.
Neither category is retained or included in later exports. Legacy imports remain permissive;
explicit unsupported transports are rejected instead of silently becoming HTTPS.

### Configuration subscriptions

**Settings → Configuration subscription** imports trusted HTTPS snapshots with optional Basic Auth,
backup URLs and scheduled refresh. Subscription-owned profiles update atomically; local profiles
and current tunnels survive. Changed connection settings require reconnecting. URLs and credentials
are encrypted locally; exported URL tokens remain sensitive.
See [English](docs/en/subscriptions.md) / [Русский](docs/ru/subscriptions.md).

### MASQUE with GOST

Select **MASQUE α (HTTP/3)** for multiplexed TCP/UDP over QUIC with Basic Auth.
GOST requires the `http3` listener, not `h3`, and a valid proxy certificate for normal verification.
Setup, fingerprints and packet-size/IPv6 limits: [English](docs/en/masque.md) / [Русский](docs/ru/masque.md).

### HTTPS with Jump

Select **HTTPS with Jump** to use two HTTPS CONNECT proxies in sequence:
phone → jump proxy → destination proxy → website. Enter the destination proxy in the main
connection fields and the first hop in **Jump HTTPS proxy**. Both ports default to 443.
The jump proxy must allow CONNECT to the destination proxy hostname and port; it resolves that
hostname. Only the jump proxy is bootstrapped on the phone. Both hops use the selected TLS
fingerprint and support HTTP/1.1 and HTTP/2 CONNECT independently.

Each hop verifies its own TLS certificate and can use separate Basic Auth credentials. The
optional shared-authentication setting reuses the destination username and password. Certificate
verification exceptions apply only to the selected hop. Connection tests and DoH use the chain;
local-network destinations still follow the existing bypass setting. A failed hop never causes
fallback to a direct connection to the destination proxy.

JSON schema version 8 stores this mode as `proxy.type: "HTTPS_JUMP"`, with first-hop settings in
`proxy.jump`: `host`, `port`, `sameAuthentication`, `username`, `password`, and
`allowInvalidProxyCertificate`. Export passwords only when needed. Older application versions
reject version 8 files, preventing a chain from being imported as a single proxy. ProxyList
exports support single HTTPS, MASQUE and SOCKS5 proxies and omit chain profiles.

HTTPS profiles, including HTTPS with Jump, also offer **Prefer HTTP/3 α** (off by default): try MASQUE
on the same host/UDP port, then HTTPS when unavailable. Certificate and
authentication errors remain terminal. HTTPS fallback blocks ordinary UDP and
shows a warning; explicit MASQUE profiles never fall back. JSON stores the
preference in `profiles[].proxy.preferHttp3`. With Jump, both nodes must support
MASQUE and a usable datagram MTU; otherwise the entire chain retains HTTPS.

### Experimental MASQUE α

MASQUE over HTTP/3 is available in `main` after [PR #70](https://github.com/andre487/AndroidMegaProxy/pull/70)
was merged. It is not included in the published v1.0.3 APKs. It adds TCP CONNECT and CONNECT-UDP
with Basic authentication and browser TLS/QUIC presets. Setup and known limits:
[English](docs/en/masque.md) / [Русский](docs/ru/masque.md).

## Current limitations

- HTTPS and SSH forward TCP only. MASQUE UDP packets must fit the negotiated QUIC datagram
  size and path MTU; GOST 3.3.0 has no reliable capsule fallback for larger packets.
- GOST 3.3.0 rejects IPv6 literal targets in CONNECT-UDP. TCP IPv6 and local-network UDP bypass
  retain the existing IPv6 policy.
- SSH private keys protected by a passphrase are not supported yet.
- Browser and SSH fingerprint presets are version-specific approximations. A preset name is not a
  permanent guarantee of an exact client fingerprint.
- Edge Android, Samsung Internet, and Yandex Browser TLS presets remain unavailable until verified
  Android ClientHello fixtures are added.
- Always-on behavior ultimately depends on Android and the device vendor. An explicit force-stop
  cannot be recovered from programmatically.

## Building from source

The command-line build does not require Android Studio. It requires:

- JDK 21
- Go 1.26.3 or newer (see `native/go.mod`)
- Network access to download the pinned `gomobile`/`gobind` tools during native builds
- Android SDK Platform 36 and Build Tools 36.0.0
- Android NDK 29.0.14206865

Example environment on macOS:

```shell
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
```

Install Ruby 3.4 and the pinned Fastlane dependencies, then build and test the application through
the project's supported automation entry point:

```shell
bundle install
bundle exec fastlane android test
```

For a debug APK without the complete check suite, run
`bundle exec fastlane android debug_artifact`. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. Gradle and the scripts under `scripts/` remain the
low-level implementation used by the Fastlane lanes. Installation details and the complete command
reference are in the [Fastlane workflow documentation](docs/en/fastlane.md) ([по-русски](docs/ru/fastlane.md)).

Install the debug APK on a connected device:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n net.megaproxy487/.MainActivity
```

The project pins Gradle 8.11.1 through the checked-in wrapper. Use `./gradlew` rather than a globally
installed Gradle version. See [native/README.md](native/README.md) for Go data-plane details.

The `test` lane runs native unit tests and Android JVM/lint/build checks. Run
`python_checks`, `native_integration` and API 26/35 device scenarios separately
when applicable; main CI runs all of them.

## Development workflow

English is the project language for source code, comments, commit messages, logs, and tooling.
User-visible UI strings are provided in English and Russian. Developer guides are maintained in
both languages; numbers and dates follow the system locale independently of the app language.

### Emulator

For optional local device testing on an ARM64 host, create the API 35 Google APIs ARM64 emulator
(the script installs the emulator and system image if missing):

```shell
./scripts/create-android-emulator.sh
```

Robolectric Compose tests do not need an emulator. CI also runs independent hardware-accelerated
API 26 and API 35 integration scenarios for real VPN/JNI, Keystore and system document providers.
See the [testing policy](docs/en/release-testing.md).
Launch emulators with `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY` and lowercase variants unset.

The script installs missing components, configures host keyboard and mouse input, and can be run
more than once. It creates `MegaProxy_API_35` by default; set `MEGAPROXY_AVD_NAME` to override the
name.

Start the application without a debugger:

```shell
./scripts/run-without-debugging.sh
```

VS Code tasks support build/install, app Logcat and JDWP attachment; JDWP covers Kotlin/Java,
not Go. Use native tests for the Go core.

### Signed release builds

Release scripts require `gomobile` on `PATH`. The `debug_artifact` and `android_checks` lanes
install the pinned native tools; run either once when preparing a fresh build environment.

Build optimized and signed APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`, plus the
universal APK used for reproducible F-Droid verification:

```shell
bundle exec fastlane android release_artifacts
```

The scripts read the default signing key from `$HOME/AndroidApkKey` and its password from
`$HOME/.my-tokens/android-key-password`. Override these with `MEGAPROXY_KEYSTORE_PATH`,
`MEGAPROXY_KEY_ALIAS`, `MEGAPROXY_KEY_PASSWORD_FILE`, and `MEGAPROXY_KEY_PASSWORD`. The signed
ABI-specific and universal APKs and `SHA256SUMS` are written to `dist/release`.

Pushing a version tag runs the same Fastlane release lane in GitHub Actions, builds and verifies
all five APKs, scans them with VirusTotal, then attaches them, their checksums and
scan reports to a GitHub Release. Hash-addressed report links appear in the release
notes. The scan also requires `VIRUSTOTAL_API_KEY`; API errors, incomplete scans and
malicious/suspicious verdicts block publication. The workflow requires
`ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
`ANDROID_KEY_PASSWORD` signing secrets. The tag must match
`v` followed by the current `versionName` in `app/build.gradle.kts`. The manual
[Prepare and merge release workflow](docs/en/release-automation.md) can prepare the
version/changelogs, wait for full PR CI, merge, and create this tag. Alternatively, create
it with `git tag` and push the specific tag with `git push origin`; never reuse a historical tag.

## Contributing

Bug reports and focused pull requests are welcome. Please avoid including proxy credentials,
private keys, destination history, or other personal data in issues and logs. Select checks using the [testing policy](docs/en/release-testing.md)
([по-русски](docs/ru/release-testing.md)); docs-only changes do not require APK builds.

## License

MegaProxy is released under the [MIT License](LICENSE).

### CI tools

Every push to main runs all Android/Compose UI, Go and Python checks; the CI badge tracks these runs.
PR CI selects checks from changes since each suite’s last successful ancestor check, with a full PR diff fallback. Use `python3 scripts/github_actions.py` to choose an open
PR and rerun all CI jobs (including skipped checks) or only failed jobs through GitHub CLI. Supports `--dry-run` and `--yes`/`-y`.
See the [English](docs/en/fastlane.md) or [Russian](docs/ru/fastlane.md) reference for scope rules,
Python formatting/tests and launcher setup.
