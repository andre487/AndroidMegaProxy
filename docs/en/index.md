# MegaProxy documentation

[Русский](../ru/index.md)

MegaProxy connects your Android device to servers you control or trust. Servers and credentials
are not included; server setup lives in [MegaProxyServer](https://github.com/andre487/MegaProxyServer).

## Start here

1. [Install MegaProxy](installation.md), then add or import a [server profile](profiles-and-routing.md).
2. Select all apps or choose apps in **Split tunneling**. Android 10+ also offers optional
   [app ownership checks](source-validation.md), off by default.
3. Tap **Connect** and approve Android's VPN request.
4. Open **Diagnostics**: the connection test starts automatically; speed tests unlock after success.

## Guides

| Guide | Covers |
| --- | --- |
| [Profiles and routing](profiles-and-routing.md) | HTTPS/SSH/Jump/SOCKS5, app selection, LAN bypass, failover, Always-on, import/export and local settings |
| [App ownership checks](source-validation.md) | Standard/Advanced UDP policies, TCP admission, ping blocking and compatibility limits |
| [DNS](dns.md) | DoH, plain TCP/UDP, routing and local security approvals |
| [MASQUE α](masque.md) | Experimental HTTP/3 setup, fingerprints and interoperability limits |
| [Diagnostics and metrics](connection-metrics.md) | Connection/speed checks, shared log, VPN visibility, passive metrics and MTU |
| [Configuration subscriptions](subscriptions.md) | HTTPS sources, refresh, profile ownership and reconnect notifications |
| [App updates](updates.md) | F-Droid/GitHub selection, daily checks, consent and version/signature rules |

## SOCKS5

TCP CONNECT and UDP ASSOCIATE with optional credentials. **SOCKS5 does not encrypt the
connection or password.** [Setup and relay limits](profiles-and-routing.md#socks5).

## HTTPS with Jump

Two proxies with independent authentication and certificate checks; optional HTTP/3 requires
both hops to support MASQUE. [Setup and fallback](profiles-and-routing.md#https-and-jump).

## Fingerprints and diagnostic logs

Fingerprints describe the proxy connection, not the applications inside it. The persistent
log rotates within a configurable storage limit; the Diagnostics screen has a separate shared
in-memory log. [Controls](profiles-and-routing.md#import-export-and-local-controls) ·
[Measured and logged data](connection-metrics.md).

## For developers

- [Fastlane commands and setup](fastlane.md)
- [Testing policy and release acceptance](release-testing.md)
- [Native benchmarks and their limits](native-benchmarks.md)
- [Release automation](release-automation.md)
- [Artwork and F-Droid graphics](graphics.md)
- [Go dataplane](../../native/README.md)
