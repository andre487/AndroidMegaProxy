# Installing MegaProxy

[Русский](../ru/installation.md)

Android 8.0 or newer is required. MegaProxy does not provide servers or credentials;
obtain a profile from your server operator. The GitHub button downloads the latest universal APK.

<table>
  <tr><th align="center">GitHub</th><th align="center">F-Droid</th></tr>
  <tr>
    <td align="center"><a href="https://github.com/andre487/AndroidMegaProxy/releases/latest/download/mega-proxy-universal.apk"><img src="../assets/get-it-on-github.png" alt="Download APK from GitHub" height="75"></a></td>
    <td align="center"><a href="https://f-droid.org/packages/net.megaproxy487/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="75"></a></td>
  </tr>
</table>

## Install and connect

**GitHub:** open the APK on your phone (browser notification or Files → Downloads). Allow
**Install unknown apps / Allow from this source** for the browser/file manager when prompted,
then Install → Open. You can revoke that installation permission afterward.
If Samsung Auto Blocker prevents installation, see [Samsung's instructions](https://www.samsung.com/us/support/answer/ANS10003636/).
Managed-device restrictions require the administrator.

**F-Droid:** install the [F-Droid client](https://f-droid.org/), refresh its catalog, open the package
link above or search for MegaProxy (`net.megaproxy487`), and install. Grant F-Droid installation
permission if Android requests it.

In MegaProxy, allow notifications for connection controls, add/import a profile, select global or
per-app routing, and tap **Connect**. Confirm Android's VPN request. **Test**, **Speed** and
**VPN visibility** are separate main-menu screens. MegaProxy's prominent split-routing switch
and its app-list checkbox control the same selection; upstream sockets remain protected from loops.

## Update safely

Use **Settings → App updates** or the original installer. Daily checks are on by default;
downloads need consent. Source selection, weekly reminders and skipped versions: [App updates](updates.md).
Install over the old version; **do not uninstall** to resolve an error, because that deletes local
profiles/settings/SSH pins. Export needed configurations before migration and protect exported secrets.

An in-place update needs the same package, compatible signer, correct ABI and a higher/compatible
version code. Keep the APK variant: ABI-specific APKs have higher codes than universal in the same
release. Switching to universal may require a newer release. Debug/other-distributor signatures
may prevent replacement; do not force a downgrade or delete a working installation.

## APK variants and verification

[Release assets](https://github.com/andre487/AndroidMegaProxy/releases/latest) contain standalone APKs:

| File | Device |
| --- | --- |
| `mega-proxy-universal.apk` | Recommended if unsure; all supported ABIs |
| `mega-proxy-arm64-v8a.apk` | 64-bit ARM phones |
| `mega-proxy-armeabi-v7a.apk` | 32-bit ARM |
| `mega-proxy-x86_64.apk` | 64-bit x86 devices/emulators |
| `mega-proxy-x86.apk` | 32-bit x86 devices/emulators |

Source ZIPs, old AABs and symbol archives are not installers. Download `SHA256SUMS` from the same
release and compare the complete hash with `shasum -a 256 FILE.apk` (macOS), `sha256sum FILE.apk`
(Linux), or `Get-FileHash FILE.apk -Algorithm SHA256` (PowerShell). A matching checksum detects
download damage, not independent authenticity. The release's **VirusTotal** section links
hash-specific reports; zero detections do not guarantee safety.

Optional [ADB](https://developer.android.com/tools/adb#move): enable USB debugging, authorize your
computer, check `adb devices`, then `adb -s SERIAL install -r FILE.apk`. Disable debugging when done.

## PR test builds

PR descriptions and Android job summaries link to 14-day artifacts. GitHub authentication/access
is required. Extract `megaproxy-pr-<number>-debug-apk` and install `app-debug.apk` on a spare device
or emulator: it shares the release package ID but uses a different, potentially per-run debug key.
Do not uninstall your everyday release just to test it. The unsigned-release artifact is for
verification and cannot be installed as a signed release. Skipped builds publish no new APK.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Package conflict / downgrade | Signer and variant/version; use a compatible newer release |
| No matching ABI | Universal or correct architecture; Android 8+ |
| Parsing error | APK rather than ZIP/AAB; checksum and complete download |
| Storage / ADB unauthorized | Free space / unlock and authorize the computer |
| Installed but cannot connect | Profile/server credentials, network and VPN permission |
