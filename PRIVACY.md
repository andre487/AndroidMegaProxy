# Privacy Policy

**Last updated: September 8, 2026**

MegaProxy does not operate a proxy service or collect configuration, usage statistics, or
telemetry on developer-operated servers. The application does not use advertising, analytics
SDKs, tracking identifiers, or automatic crash-report uploads.

## Local application data

MegaProxy stores proxy profiles, settings and trusted SSH host keys on the device. Proxy passwords
and imported private keys are encrypted with AES-GCM using a key held by Android Keystore.
Android cloud backup and device-to-device transfer are disabled for application data.

Profile exports may contain credentials if the user explicitly includes them. Exported files and
copies shared with other applications are outside MegaProxy's local data storage.

## Network connections

MegaProxy forwards selected application traffic through the user's configured HTTPS or SSH
servers. Authentication credentials are sent to the configured servers as required by the chosen
protocol. Those servers can observe connection metadata and destinations. Application TLS is
preserved; MegaProxy does not install a CA or intercept application TLS.

DNS requests use the configured DNS-over-HTTPS provider and permitted fallback providers. Before
the tunnel exists, resolving the proxy hostname may contact Cloudflare, Yandex, Google or Quad9
bootstrap resolvers directly. Local-network bypass and per-app routing settings determine which
application traffic uses the tunnel.

The user-initiated connection test contacts `example.com` through the proxy and uses external
services to determine the exit IP and country. IP providers are `ifconfig.me`, `api.ipify.org` and
`icanhazip.com`; country providers are `ifconfig.co`, `ipapi.co` and `api.country.is`. Providers are
tried in order as needed. These services see the connection's exit IP and process the requests
under their own policies. These requests are connection diagnostics, not developer telemetry.

## Diagnostic logs and sharing

The application keeps size-limited diagnostic and crash logs locally for troubleshooting.
Log sanitization is designed to remove credentials, addresses and sensitive request details;
users should review any report before sharing it. Logs are not automatically uploaded.

The user can explicitly export profiles, copy diagnostic information, or open a feedback/crash
report in an email application. The user controls whether to send that report and its attachments.

## Data retention and deletion

The developer does not maintain a server-side database of application profiles or usage data.
Local application data and diagnostic logs can be removed by clearing the application's data or
uninstalling it. Exported files and copies already shared with other applications must be deleted
separately.

## Contact

Questions about this policy: megaproxy-feedback@hotmail.com
