# Privacy Policy

**Last updated: September 8, 2026**

MegaProxy is an Android VPN client for proxy servers you choose. It does not provide a proxy
service, require an account, or automatically send usage data or crash reports to the developer.
It contains no advertising, analytics SDKs or tracking services.

## Data used on your device

MegaProxy stores your connection profiles, credentials, settings and trusted SSH host keys to
connect to your servers. Passwords and imported private keys are encrypted with a key held by Android Keystore. Android backup and device transfer are disabled for app data.

For per-app routing, MegaProxy reads the applications visible to it on your device and stores
which applications you select. This information is used locally to configure routing, not
uploaded as an application inventory. Profile exports can include these routing selections.

Connection statistics and diagnostic/crash logs are processed locally to display connection
status and help troubleshoot failures. Logs contain operational events and error details;
filtering is designed to remove credentials and sensitive addresses. Review reports before sharing.

## Data sent to network services

MegaProxy forwards selected application traffic to your configured proxy servers and sends the
authentication information needed to connect. SSH authentication does not transmit private keys. Proxy
operators can see connection metadata and destinations, and unencrypted application content.
Choose operators you trust.

DNS providers receive the names being resolved. Before connecting, MegaProxy may resolve your
proxy's hostname directly through Cloudflare, Yandex, Google or Quad9; these resolvers can see
your source IP address and the proxy hostname. DNS queries through the tunnel use the configured
provider and permitted fallbacks.

When you run a connection test, MegaProxy contacts a test website and external IP/country lookup
services through the proxy. They receive the exit IP and test requests to check connectivity and
identify the proxy's apparent country, not your GPS location. The current services are listed in
[Network privacy details](README.md#privacy-and-security). These providers handle requests under
their own policies; MegaProxy does not control their retention practices.

## Sharing and contacting support

Exporting or sharing a profile can disclose its settings and, if explicitly included, passwords
or private keys to the destination you choose. Copying diagnostics places them on the clipboard.

If you choose to share a feedback or crash report, MegaProxy passes it to the application you
select. The prepared report contains device model, Android/app versions, a summary of connection settings and
a diagnostic log attachment. You control whether to send it. The receiving application handles
the shared copy under its own policies.

If you email support, the developer receives your sender address, message and any attachments
you send for handling your request. Uninstalling MegaProxy does not delete that correspondence.

## Retention and deletion

Profiles and settings remain locally until you change/delete them or clear app data. Diagnostic
logs rotate within a configurable size limit; you can clear them in the diagnostic-log screen.
Clearing app data or uninstalling removes local app files, including cached report attachments.
Exported files and copies shared with other applications must be deleted separately.

Support emails, including the sender address, message and attachments, are retained until the
reported problem is fixed, then deleted. You can contact the developer about your correspondence
at the address below.

## Contact

For privacy questions or requests about information you sent to support, contact the MegaProxy
developer at megaproxy-feedback@hotmail.com.
