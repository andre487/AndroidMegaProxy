# Strict source validation

[Русский](../ru/source-validation.md) · [Documentation](index.md)

In **Split tunneling**, optional **Strict source validation** requires Android 10+ and
applies when the VPN reconnects. It is local to the device, defaults to off and is not
included in profile exports or subscriptions. With it off, the native stack retains its
original transport and link handlers: no UID queries, validation caches or packet checks.

Both levels check TCP ownership once before proxy/direct-LAN forwarding, adding latency
when a connection is established, rather than repeating ownership checks on TCP data.
UDP, including DNS, is checked at admission. Choose the UDP policy:

| Level | UDP ownership checks | Tradeoff |
| --- | --- | --- |
| Standard (initial choice) | Admission and the first outgoing datagram after a fixed 1-second interval | Fewer Android API calls; a reused port may temporarily inherit an old permission |
| Advanced | Admission and every outgoing datagram | Earlier detection of owner changes; more CPU work and latency |

The Standard cache belongs only to the specific live flow. It is not shared by source port,
destination or subsequent flows. Cache hits do not extend the deadline; no background timer
is needed. Expired permission must be rechecked before forwarding the next datagram. A denied
flow is closed. This deliberately allows a stale-owner window of up to 1 second between
checks and does not offer the same protection as Advanced. Neither level makes ownership
lookup and forwarding an atomic kernel operation.

Unknown owners and lookup exceptions are denied when checked. Split mode accepts only the
selected application UIDs; global mode requires an identifiable owner. Packages sharing a UID
cannot be distinguished. Slow new-flow lookups run outside the shared TUN loop with at most
64 concurrent admissions. At most 8 ownership lookups can enter Android/JNI concurrently,
shared across active and retiring sessions; excess requests fail closed. The query budget
is initialized only when protection is enabled. Numeric UDP addresses are prepared once
per flow. The ICMP filter reads only short headers for ordinary packets; unusually long
extension chains use one packet copy rather than repeatedly coalescing growing prefixes.
Strict UDP relays close both TUN and upstream when either direction fails, so a denied
source cannot retain an idle proxy association until the normal UDP timeout. Both levels may disrupt apps or traffic
without a visible owner, such as tethering. No direct retry bypasses a denial.

The selected level is also device-local and applies on reconnection. Existing enabled settings
without a level migrate to Advanced, preserving their previous behavior; fresh opt-in uses
Standard. Visibility reports the actual running level, including when a level change is pending.

IPv4/IPv6 Echo (ping) is blocked before the userspace stack; ordinary ICMP errors retain
normal processing. Fragmented ICMP and IPv6 fragments with an unidentifiable transport are
also dropped. The physical network's ICMP is unaffected. The app's TCP/HTTP diagnostics
continue working. **Visibility** reports the active tunnel's protection and whether a
reconnect is needed. This is a session status, not an attack test from another application,
and does not hide VPN interfaces or other system-visible VPN signals.
