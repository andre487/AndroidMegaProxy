# MegaProxy documentation

Server configurations and setup instructions have moved to the dedicated
[MegaProxyServer repository](https://github.com/andre487/MegaProxyServer).

Client documentation:

- [Configuration subscriptions](subscriptions.md)
- [Installation](installation.md)
- [App updates](updates.md)
- [Connection metrics, Test, Speed and VPN visibility](connection-metrics.md)
- [SOCKS5](#socks5)
- [MASQUE α (HTTP/3), experimental](masque.md)
- [HTTPS with Jump](#https-with-jump)

Developer documentation:

- [Fastlane workflow](fastlane.md)
- [Testing policy and release acceptance](release-testing.md)
- [Release automation](release-automation.md)

## SOCKS5

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

## HTTPS with Jump

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

[Русская версия](../ru/index.md)
