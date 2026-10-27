# DNS

DoH is the default. Custom endpoints accept HTTPS URLs with domain names, IPv4 or bracketed IPv6,
optional ports, paths and query strings. A missing path means `/`; the server must speak wire-format
DoH (`application/dns-message`). HTML or ordinary DNS/DoT is not DoH. TLS/HTTP failures do not prove
that an endpoint is not DoH.

**Allow invalid DoH certificates** requires confirmation and permits self-signed, expired and
hostname-mismatched certificates. It affects only DoH, never proxy/jump certificate verification.
Leave it off unless you trust the network path: encryption alone does not authenticate the resolver.

Enable **Insecure DNS** locally to select a plain resolver. Enter a numeric IPv4 or bracketed IPv6
address, optionally with a port (default 53). No hostname/bootstrap lookup is performed.

| Mode | Route and encryption |
|---|---|
| DoH | Through the proxy chain; HTTPS encryption to the resolver |
| TCP DNS | Through the proxy chain, including private resolver IPs despite LAN bypass; plaintext after the proxy |
| UDP DNS, actual MASQUE/HTTP3 | Through CONNECT-UDP; encrypted to the proxy, plaintext after it |
| UDP DNS, SOCKS5 | Through UDP ASSOCIATE; SOCKS5 adds no encryption |
| UDP DNS, actual HTTPS/TCP or SSH | Protected direct socket outside the VPN; plaintext throughout |

An HTTPS profile preferring HTTP/3 uses the actual selected transport. A failed tunneled UDP query
never falls back to direct UDP. Plain modes have no public DoH fallback. IPv6 policy and cancellation
apply to every mode. App UDP/TCP port 53 is intercepted; app-specific encrypted DNS follows normal routing.

Portable profiles retain the endpoint in the legacy `dns.customDohUrl` string with an explicit
`https://`, `tcp://` or `udp://` scheme and `dns.provider: "CUSTOM"`. Examples:
`https://dns.example/dns-query`, `tcp://192.0.2.53:53`, `udp://[2001:db8::53]:53`.
The legacy field name and version 8 schema are unchanged. Clients without plain DNS support
cannot use TCP/UDP endpoints; they must not reinterpret them as DoH.
Security consent stays on this device: exports/imports and subscriptions cannot grant
permission for insecure DNS or invalid DoH certificates. On a new device, confirm locally before connecting. Refreshing/importing an unchanged resolver preserves its local
approvals; a changed resolver requires new approval.
Query tokens in custom DoH URLs remain in exported JSON even when passwords are omitted.
Treat the file as sensitive.
Proxy bootstrap DNS precedes the VPN; see the README endpoint inventory.

DNS admission is limited to 64 flows per proxy, shared by intercepted TCP and UDP. When full,
the oldest completed UDP flow is evicted together with its TUN mapping. Pending responses
and TCP DNS streams are retained.
Excess flows fail immediately; stopping the VPN closes admitted flows and cancels their queries.

DoH alternatives start 200 ms apart; the first valid response wins and outstanding requests are
cancelled. Slow requests may therefore reach multiple configured providers. CUSTOM and filtering
presets have no fallback providers. Proxy bootstrap uses the same strategy with its existing
provider list, independently of profile DNS. Plain DNS has only the selected resolver.

## Strict source validation

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
