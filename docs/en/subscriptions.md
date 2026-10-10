# Configuration subscriptions

In **Settings → Configuration subscription**, enter the final HTTPS URL and any
backup URLs (one per line, up to seven). Enable separate Basic Auth only if the
source requires it. Save the interval in minutes (1–10080, default 60). Trust every
listed source: all receive the same credentials and may change subscribed profiles
and supported app preferences. Redirects are rejected, including HTTPS redirects.

Saving a new source schedules its first check immediately. Android battery,
network and background restrictions may delay checks. Pause disables automatic
checks; **Update now** refreshes the saved sources even while paused. Each refresh
starts at the primary URL, tries sources in order, stops after a valid import and
shows its successful source number. Error/warning status and the last success are
visible; errors never show remote response text or secret URLs.

You can also bootstrap by importing a complete configuration with this fragment:

```json
{
  "schema": "net.megaproxy487.config",
  "version": 8,
  "subscription": {
    "url": "https://configs.example.com/team.json",
    "fallbackUrls": ["https://backup.example.com/team.json"],
    "intervalMinutes": 60,
    "enabled": true
  },
  "profiles": [{
    "id": "team-proxy",
    "name": "Team proxy",
    "proxy": { "type": "SOCKS5", "host": "proxy.example.com", "port": 1080 }
  }]
}
```

Imported profiles become owned by this subscription. A subscription configured in
Settings initially owns no local profiles. A successful update replaces its owned
profiles and removes those missing from the snapshot, preserving separately added
profiles. A conflicting ID belonging to a local profile rejects that source.
Surviving selections remain; removed selections use the downloaded
`activeProfileId`, then the first downloaded profile. Bootstrap never starts VPN.

Supported bodies match manual import: MegaProxy JSON, FoxyProxy JSON, ProxyList
and SuperProxy text. JSON uses stable IDs; other formats reuse uniquely matching
owned names, then unique type/host/port. Ambiguous matches get new IDs. Canonical
snapshots reset omitted supported app preferences to defaults; other formats
preserve settings they do not represent. Omitted profile secrets retain local
credentials and explicit empty fields clear them. An invalid, empty or oversized
snapshot keeps the last working configuration. See the
[delivery protocol](https://github.com/andre487/MegaProxyConfig/blob/main/docs/subscription-protocol.md).

Downloads follow Android routing for MegaProxy. Excluding MegaProxy from per-app
VPN routing makes them use its ordinary network; a disconnected VPN is not enabled
automatically. Requests use verified TLS, no redirects, a 4 MiB decoded-body limit,
15-second connect/read timeouts and a 45-second read budget. They identify the app
with `X-MegaProxy-Client: android` and `X-MegaProxy-Version`.

Existing tunnels keep their previous settings. A change affecting the active
connection marks **Reconnect**, also explained on the subscription screen. Reconnect
the VPN to apply it; a metadata-only change does not require reconnecting.
Automatic updates that change the running VPN settings also send a notification
with **Reconnect**, if notifications are allowed. The notification contains no
profile names, addresses or credentials. Its action only applies to the current
pending change while the VPN is running; it cannot restart a stopped VPN.
Open editors save only deliberately edited fields, preserving other refreshed values.
Turning off Basic Auth in the subscription screen clears both credentials.

URL query tokens and separate subscription credentials are encrypted at rest.
Removing the subscription keeps profiles. Import without `subscription` retains
its settings; explicit null removes it. An omitted password retains the saved one
only for the same normalized URL and username; empty clears it. Downloaded
subscription settings never replace your configured source list or credentials.
JSON exports include the definition; its password follows **Include passwords**,
but query tokens in URLs remain present. Ownership and status are never exported.
