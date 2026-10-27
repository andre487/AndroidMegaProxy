# App ownership checks

[Русский](../ru/source-validation.md) · [All guides](index.md)

**Strict source validation** checks which app owns TCP/UDP traffic before forwarding it.
Enable it in **Settings → Split tunneling** on Android 10+. It defaults to off, applies on
reconnection and stays on this device: exports and subscriptions cannot enable it.
With it off, the original native path has no UID queries, validation caches or added packet checks.

## Levels and cost

Both levels check TCP ownership once at connection admission, including direct-LAN traffic;
they can delay connection setup but do not repeat ownership checks on TCP data.
UDP, including DNS, is checked at admission and then according to the selected level:

| Level | Outgoing UDP checks | Tradeoff |
| --- | --- | --- |
| Standard (initial choice) | First datagram after each fixed 1-second interval | Fewer Android API calls; stale permission may last up to 1 second |
| Advanced | Every datagram | Earlier detection of owner changes; more CPU work and latency |

Standard caches permission only for that live flow, not for other destinations or later flows.
Cache hits do not extend the deadline. The next datagram after expiry requires a new check;
a denied flow is closed. **Standard does not offer the same protection as Advanced.** Neither
makes owner lookup and forwarding an atomic kernel operation.

## Blocking and compatibility

Unknown owners and lookup errors are denied when checked. Split routing accepts only selected
app UIDs; global routing requires an identifiable owner. Apps sharing a UID cannot be distinguished.
Both levels can disrupt traffic without a visible owner, such as tethering. No direct retry
bypasses a denial. Lookups run outside the shared TUN loop, limited to 64 concurrent admissions
and 8 Android/JNI queries shared across active and retiring sessions; excess requests are denied.

Both levels block IPv4/IPv6 Echo (ping) in the TUN path, fragmented ICMP and IPv6 fragments with
an unidentifiable transport. Ordinary ICMP errors keep normal processing; the physical network's
ICMP is unaffected. TCP/HTTP diagnostics continue working.

## Check the active protection

**Visibility** shows the running level and whether a reconnect is needed, rather than merely
the saved setting. This is a session status, not an attack test from another app, and does not
hide VPN interfaces or other system-visible VPN signals. Changing the level also requires
reconnection. Previously enabled settings without a level retain Advanced; fresh opt-in uses Standard.
