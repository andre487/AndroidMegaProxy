# Strict source validation

[Русский](../ru/source-validation.md) · [Documentation](index.md)

In **Split tunneling**, optional **Strict source validation** requires Android 10+ and
applies when the VPN reconnects. It is local to the device, defaults to off and is not
included in profile exports or subscriptions. With it off, the native stack retains its
original transport and link handlers: no UID queries, validation caches or packet checks.

When enabled, TCP ownership is checked before proxy/direct-LAN forwarding; UDP ownership is
checked on admission and for every outgoing datagram, including DNS, without a positive
cache that could outlive the Android socket. Unknown owners, lookup exceptions and stale
sessions are denied. Split mode accepts only the selected application UIDs; global mode
requires an identifiable owner. Packages sharing a UID cannot be distinguished. The
ownership lookup and forwarding are separate operations, not an atomic kernel guarantee.
Slow new-flow lookups run outside the shared TUN loop with at most 64 concurrent admissions;
excess attempts are closed. UDP checks add latency and CPU work and may disrupt apps or
traffic without a visible owner, such as tethering. No direct retry bypasses a denial.

IPv4/IPv6 Echo (ping) is blocked before the userspace stack; ordinary ICMP errors retain
normal processing. Fragmented ICMP and IPv6 fragments with an unidentifiable transport are
also dropped. The physical network's ICMP is unaffected. The app's TCP/HTTP diagnostics
continue working. **Visibility** reports the active tunnel's protection and whether a
reconnect is needed. This is a session status, not an attack test from another application,
and does not hide VPN interfaces or other system-visible VPN signals.
