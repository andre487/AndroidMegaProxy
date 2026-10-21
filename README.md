# MegaProxy

[![CI](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)
[![Release](https://img.shields.io/github/v/release/andre487/AndroidMegaProxy)](https://github.com/andre487/AndroidMegaProxy/releases/latest)
[![VirusTotal: latest report](https://img.shields.io/badge/VirusTotal-latest%20report-blue?logo=virustotal&logoColor=white)](https://github.com/andre487/AndroidMegaProxy/releases/latest#virustotal)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)

<p align="center">
  <img src="assets/branding/megaproxy-launcher-master.png" width="160" alt="MegaProxy app icon">
</p>

MegaProxy is an open-source Android VPN client for HTTPS, SOCKS5, MASQUE α (HTTP/3)
and SSH servers you control or trust, including **HTTPS and SSH jump hosts** for multi-hop connections.
Route all apps or selected apps through your server.
No account, ads, analytics or remote telemetry. **Servers and credentials are not included.**

## Installation

Android 8.0+ is required. Choose GitHub's universal APK or install through F-Droid:

<table>
  <tr><th align="center">GitHub</th><th align="center">F-Droid</th></tr>
  <tr>
    <td align="center"><a href="https://github.com/andre487/AndroidMegaProxy/releases/latest/download/mega-proxy-universal.apk"><img src="docs/assets/get-it-on-github.png" alt="Download APK from GitHub" height="75"></a></td>
    <td align="center"><a href="https://f-droid.org/packages/net.megaproxy487/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="75"></a></td>
  </tr>
</table>

[Installation guide](docs/en/installation.md) · [Установка на русском](docs/ru/installation.md).
For updates, use **Settings → App updates** or your installer; keep the same APK variant and
install over the existing app to preserve profiles. PR APKs are test builds.

1. Add or import a server profile.
2. Choose global routing or select apps for split tunneling.
3. Run **Test** from the main menu, then **Connect** and approve Android's VPN request.

Server setup: [MegaProxyServer](https://github.com/andre487/MegaProxyServer).
All guides: [English](docs/en/index.md) / [Русский](docs/ru/index.md).

## Features

- **HTTPS and SSH jump hosts** with separate authentication and trust checks for each hop.
- HTTPS CONNECT over HTTP/1.1 or multiplexed HTTP/2; optional HTTP/3 preference with HTTPS fallback.
- SOCKS5 TCP/UDP, experimental MASQUE TCP/UDP over HTTP/3 and SSH.
- Per-app routing, local-network bypass, optional IPv6 and Android Always-on VPN.
- Multiple profiles, import/export, HTTPS configuration subscriptions and automatic failover.
- DNS-over-HTTPS, optional plain TCP/UDP DNS, selectable TLS/QUIC/SSH fingerprints and custom JA3.
- Traffic totals/rates, passive TCP/QUIC metrics, connection tests, download/upload speed tests
  and VPN visibility with ProxyCheck IP reputation.
- Local diagnostic logs, encrypted stored credentials, English and Russian interfaces.

[Diagnostics and measurement limits](docs/en/connection-metrics.md) ·
[Subscriptions](docs/en/subscriptions.md) · [App updates](docs/en/updates.md).

### SOCKS5

Supports optional username/password authentication and UDP ASSOCIATE. **SOCKS5 does not encrypt
the proxy connection or credentials**; application TLS and DoH keep their own encryption.
[Setup and limits](docs/en/index.md#socks5).

### HTTPS with Jump

Chains two HTTPS proxies with separate authentication and certificate verification.
HTTP/3 preference requires MASQUE support on both hops; otherwise the whole chain uses HTTPS.
A failed hop never causes a direct connection around it. [Setup](docs/en/index.md#https-with-jump).

### MASQUE α

Experimental HTTP/3 TCP CONNECT and CONNECT-UDP. GOST needs the `http3` listener, not `h3`;
packet-size, fingerprint and server IPv6 limits apply. [Setup and limits](docs/en/masque.md).

## Privacy and security

MegaProxy does not intercept application TLS or install a CA certificate. HTTPS/MASQUE certificates
and SSH host keys are verified by default; optional trust exceptions weaken those protections.
Proxy operators can see connection metadata/destinations and unencrypted traffic, so choose trusted servers.
Passwords/private keys are stored encrypted with AES-GCM and an Android Keystore-held key;
Android backup is disabled. Logs stay local unless shared explicitly. [Privacy policy](PRIVACY.md).

Network services receive the request and the source IP of its route. Diagnostics are explicit;
configured subscriptions and enabled app-update checks can also run in the background.

| Purpose | Services and routing |
| --- | --- |
| Proxy bootstrap DNS | Cloudflare, Yandex, Google or Quad9 DoH may be contacted directly before VPN exists |
| Application DNS | DoH through the proxy by default; optional TCP DNS through the proxy or UDP DNS (direct for HTTPS/TCP and SSH). Intercepted UDP/TCP port 53 only; app-specific encrypted DNS follows normal routing. [Routing and security](docs/en/dns.md) |
| Connection test | `example.com`; exit IP/country from `speed.cloudflare.com/cdn-cgi/trace`, then IP fallbacks `ifconfig.me`, `api.ipify.org`, `icanhazip.com` and country fallbacks `ifconfig.co`, `ipapi.co`, `api.country.is` |
| UDP/HTTP3 test | `www.cloudflare.com/cdn-cgi/trace` and `quic.browserleaks.com/`, independently through MASQUE/SOCKS5 UDP with verified certificates and no TCP fallback |
| VPN visibility | Route IP lookup as above, then `https://proxycheck.io/v3/`; successful reputation results cached per IP for 24 hours, quota exhaustion shown separately |
| Speed | `https://h3.speed.cloudflare.com/cdn-cgi/trace`, `/__down`, `/__up`; underlying/VPN networks separately, 10/25/50 decimal MB per direction (up to four times the selected amount plus overhead) |
| Subscriptions | Configured HTTPS sources receive their URL/query, optional Basic Auth, app version and `android` client ID; normal MegaProxy routing applies |
| App updates | Selected F-Droid or GitHub source; [metadata/download endpoints and consent](docs/en/updates.md#network-and-implementation-details) |

Speed defaults to h3 → h2 → h1; fallback only applies to initial protocol negotiation.
Latency is median time to first byte from three requests, not passive proxy RTT. Leaving the
screen cancels transfers; backgrounding alone does not. Its temporary timing log excludes IPs
and response payloads. [Details](docs/en/connection-metrics.md#test-speed-and-vpn-visibility).
UDP probes test the proxy path, not every app's routing; fingerprint presets approximate handshakes,
not complete browser behaviour. ProxyCheck verdicts and local VPN heuristics are not proof of anonymity.

## Configuration

Import MegaProxy JSON, ProxyList, FoxyProxy JSON or supported SuperProxy text. Portable JSON version 8
uses stable profile IDs; omitted secrets preserve saved values only for unchanged endpoints and accounts;
explicit empty values clear them.
Unsupported explicit transports are rejected. [Schema and examples](config-schema/) ·
[MegaProxyConfig contract](https://github.com/andre487/MegaProxyConfig).
Trusted [configuration subscriptions](docs/en/subscriptions.md) refresh owned profiles without
replacing local profiles or interrupting current tunnels; changed live settings offer reconnect.
Exported credentials and URL tokens are sensitive.
JSON transfers profiles and portable settings, not a full app backup. MTU, DNS security approvals,
language, traffic units and update preferences stay local. ProxyList exports only standalone
HTTPS/MASQUE/SOCKS5 endpoints; use JSON for SSH, Jump and other settings.

## Limitations

- HTTPS over TCP and SSH do not forward arbitrary UDP; MASQUE/SOCKS5 require a reachable UDP path.
- MASQUE remains experimental; QUIC datagram-size and GOST interoperability limits are documented
  in the [MASQUE guide](docs/en/masque.md#fingerprints-and-limits).
- Passphrase-protected SSH private keys are unsupported. Fingerprint presets are approximations.
- Android/OEM background restrictions still apply; explicit force-stop cannot be bypassed.

## Building from source

Use JDK 21, Go 1.26.3+ (`native/go.mod`), Ruby 3.4.10, Android SDK Platform 36 /
Build Tools 36.0.0 and NDK 29.0.14206865. Set `ANDROID_HOME`; native builds download pinned
Go tooling. JDK discovery uses `JAVA_HOME`, macOS `java_home` or `PATH` without a fixed install path.

```sh
bundle install
bundle exec fastlane android debug_artifact
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Use the checked-in Gradle wrapper through
Fastlane. Setup, tests, emulator and signing: [English](docs/en/fastlane.md) / [Русский](docs/ru/fastlane.md).
[Go dataplane](native/README.md) · [Release automation](docs/en/release-automation.md).

## Contributing

Select checks using the [testing policy](docs/en/release-testing.md) ([по-русски](docs/ru/release-testing.md)).
Keep credentials, private keys and personal traffic out of issues/logs. Focused bug reports and PRs are welcome.

## License

[MIT](LICENSE). Bundled dependencies and artwork retain their own licenses/notices.
