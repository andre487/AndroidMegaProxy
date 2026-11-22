# Profiles and routing

[Русский](../ru/profiles-and-routing.md) · [All guides](index.md)

## Profiles

Open **Settings → Profiles** to add, clone, rename, reorder or delete a server profile.
Names, country flags and colors help distinguish servers; long-press and drag to reorder,
or use the move actions. Selecting a different profile while connected reconnects the VPN.

| Scope | Settings |
| --- | --- |
| Per profile | Transport, server/Jump addresses, credentials and trust, DNS, IPv6, HTTP/3 preference, optional authentication check host |
| Shared across profiles | App routing, LAN bypass, fingerprints, SSH authentication/keepalive/channel/rotation settings, failover |
| Device-local, not transferred | MTU, source validation and level, language, traffic units, update preferences; DNS security approvals stay local to each profile |

Edits affecting a running connection need reconnection. Subscription changes leave the current
tunnel running and offer **Reconnect**; [subscription ownership and refresh rules](subscriptions.md).

The default TLS fingerprint is Firefox Android. Selecting a named preset keeps that choice
across updates. In a config file or subscription, use the following document-level section
to follow the application's default:

```json
"android": { "version": 1, "tls": { "fingerprint": "default" } }
```

Android also accepts `DEFAULT`. It reads the deprecated `tls.fingerprint` when the Android
preset is absent. Omitting both preserves the current selection on import and refresh.
Android exports the global JA3 as `android.tls.customJa3`. The per-profile QUIC JA3
remains in `profiles[].tls.customJa3`. The other Android version 1 fields are:

| Section | Fields |
| --- | --- |
| `android` | `alwaysOnProfileId` |
| `android.ssh` | `fingerprint`, `keepaliveSeconds`, `maxChannels`, `rotationMinutes`, `rotationMb` |
| `android.routing` and `profiles[].android.routing` | `routeAllApps`, `selectedPackages` |

For each field, Android reads its old location when the corresponding Android
section omits it. An unsupported Android section version is ignored. SSH
authentication mode, LAN bypass and the diagnostic log size remain shared fields.

## Server types

MegaProxy provides no servers. Obtain a profile from an operator you trust or use
[MegaProxyServer](https://github.com/andre487/MegaProxyServer) to set up your own.

| Type | Default port | Forwarding |
| --- | --- | --- |
| HTTPS | 443 | TCP through HTTP/1.1 or multiplexed HTTP/2 CONNECT; optional HTTP/3 preference |
| SOCKS5 | 1080 | TCP CONNECT and UDP ASSOCIATE; no proxy-hop encryption |
| MASQUE α | 443 | Experimental TCP and UDP over HTTP/3; [compatibility and limits](masque.md) |
| SSH | 22 | TCP through `direct-tcpip` channels |
| HTTPS / SSH with Jump | 443 / 22 per hop | Phone → jump server → destination proxy → website |

These are ordinary traffic capabilities; [DNS has separate routing rules](dns.md).
Application TLS remains end-to-end: MegaProxy does not install a CA or decrypt it.

### HTTPS and Jump

Enter the destination proxy in the main fields. For **HTTPS with Jump**, enter the first hop
in **Jump HTTPS proxy**. It must allow CONNECT to the destination hostname and port, and
resolves that hostname; only the first hop is bootstrapped on the phone. Each hop has its own
TLS verification and Basic Auth, or can reuse the same credentials. Both apply the selected
TLS fingerprint and negotiate HTTP/1.1 or HTTP/2 independently. A failed hop is never bypassed directly.

**Prefer HTTP/3 α** is off by default. It tries MASQUE on the same host/UDP port, then HTTPS
if unavailable; authentication and certificate errors are terminal. Jump needs MASQUE and a
usable datagram MTU on both hops, otherwise the entire chain uses HTTPS/TCP.
HTTPS/TCP fallback blocks ordinary UDP and shows a warning. Explicit MASQUE never falls back.

### Masked authentication errors

Some proxies return a decoy website or stall instead of exposing an authentication failure. A timeout or HTML response alone cannot reliably identify a decoy.

For a regular **HTTPS** profile, **Authentication check host** is optional. Enter the operator-provided knock hostname, without a URL, path or port. During HTTPS-over-TCP diagnostics the app sends an authenticated CONNECT to that hostname on port 443 through the proxy, bounded to five seconds. An unmasked `407` confirms an authentication rejection and stops the check. `200` only confirms that CONNECT was accepted; the ordinary end-to-end HTTPS test still runs. Other statuses and timeouts are inconclusive and do not get labelled as wrong credentials.

A blank field adds no probe request. JSON uses `profiles[].proxy.knockHost`; legacy
`profiles[].browser.knockHost` is accepted only when the shared field is absent. An
explicit empty shared value disables the probe, even if the legacy alias is set.
Other browser options remain ignored.

This probe applies to regular HTTPS over TCP, not HTTPS Jump or selected HTTP/3. Editing the diagnostic host does not reconnect a running VPN; a new check uses it for the same runtime endpoint while preserving runtime credentials. It adds no per-packet checks.

The shared diagnostic log records `proxy_auth_probe` start, skipped/accepted/rejected/inconclusive outcome, HTTP status, elapsed time and safe failure class. It contains neither the probe/proxy hostname nor IPs, usernames, passwords or response bodies.

### SOCKS5

Leave both authentication fields empty, or supply a username and password of 1–255 UTF-8 bytes
each. **The proxy connection and credentials are not encrypted**; application TLS and DoH keep
their own encryption. Browser TLS fingerprints do not apply. There is no SOCKS5 Jump mode.

For GOST, enable UDP: `gost -L 'socks5://user:password@:1080?udp=true'`.
The advertised UDP relay must be reachable through NAT/firewall. A wildcard address uses the
proxy bootstrap IP; a zero port or hostname relay is rejected without system DNS.

### SSH and Jump

Import an OpenSSH or supported PEM private key, or use account password authentication.
Fill in the optional passphrase field in the profile editor to save it on this device with Android Keystore protection. Leave it empty to enter the passphrase when connecting manually, or cancel the connection. A passphrase entered in the dialog stays in process memory for reconnects.
Jump and destination keys have separate passphrases when using separate credentials. Exporting
private keys preserves their original protection. Passphrases stay local and replacing the key clears its passphrase.
Encrypted PKCS#8 is unsupported. Verify the SSH host-key fingerprint with your operator before trusting it;
Jump and destination pins are separate. Disabling host-key or proxy-certificate verification
permits server impersonation.

**SSH with Jump** reaches the destination SSH server through the first SSH connection, with
separate credentials/keys or shared authentication. In **Settings → Fingerprints**, choose
automatic, password-only or key-only authentication, keepalive interval, maximum channels and
session rotation by time/traffic. Rotation waits until active channels finish. SSH presets
change the client banner and algorithm preferences, not the applications inside the tunnel.

## Routing and app ownership checks

In **Settings → Split tunneling**, choose all apps or select the apps that should use the VPN.
MegaProxy's own switch and its app-list checkbox are the same selection. MegaProxy is included
by default; excluding it later is preserved and makes its diagnostics, subscriptions and other
requests follow the ordinary network. LAN bypass sends supported local traffic directly;
it is not a fallback after proxy failure. IPv6 is enabled per profile.

**Source validation** optionally checks which app owns TCP/UDP traffic on Android 10+.
Split tunneling defaults to Standard and all-app routing defaults to off. Changes apply on reconnection. Standard rechecks a live UDP flow after
one second without outgoing traffic; Strict checks every outgoing datagram. Both check new TCP connections
and block unknown owners and TUN ping. It can delay TCP setup, slow UDP or disrupt apps;
[source-check behavior and limits](source-validation.md). **Visibility** shows the active level.

## Failover and Always-on

**Settings → Failover** can try selected profiles or all profiles after repeated connection
failures; it defaults to off. Keep at least two eligible profiles. Authentication, certificate
and configuration errors do not trigger switching. Automatic switching can change the public
IP and apparent country; the notification identifies the active profile.
[Failure detection limits](connection-metrics.md).

Choose a startup profile in **Settings → Always-on VPN**, then enable Always-on and, if needed,
**Block connections without VPN** in Android's VPN settings. Changing the startup profile
while Always-on is active reconnects immediately. Android/OEM background limits and explicit
force-stop still apply. Use **Settings → Battery settings** if Android restricts background VPN work.

## Import, export and local controls

Import MegaProxy JSON, ProxyList, FoxyProxy JSON or supported SuperProxy text. Import reports
ignored browser-only or unknown fields. Android exports JSON version 9 and can import versions 1–9. JSON preserves stable profile IDs and portable
settings; explicit unsupported transports are rejected. ProxyList exports only standalone
HTTPS/MASQUE/SOCKS5 endpoints; use JSON for SSH, Jump and other settings.

Passwords and SSH private keys are optional export choices. Omitted secrets preserve existing
values only for the same endpoint/account; explicit empty values clear them. URL query tokens
remain in JSON even when passwords are omitted. Exported credentials/keys are readable: protect
the file. A JSON export is not a full app backup. [Pinned schemas and examples](../../config-schema/);
[complete field reference](https://github.com/andre487/MegaProxyConfig/blob/main/docs/field-reference.md).

**Settings → Traffic units** selects IEC (default, powers of 1024: KiB/MiB) or SI (1000: KB/MB)
for totals and speeds; traffic-limit inputs remain in MiB. Language is selected separately
from the system locale used for numbers and dates. **MTU** is device-local and applies on
reconnection; [MTU choices](connection-metrics.md#vpn-interface-mtu).

**Diagnostic log** can be inspected, exported or cleared, with a rotating 1–100 MiB storage
limit. Feedback can attach it using the mail app you choose; review before sending.
[Diagnostics, speed and visibility](connection-metrics.md#test-speed-and-vpn-visibility) describe
the shared test log and measurement limits; [privacy policy](../../PRIVACY.md).
