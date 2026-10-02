# Installing MegaProxy from an APK

[Русская инструкция](../ru/installation.md)

MegaProxy requires Android 8.0 (API 26) or newer. Signed release APKs are available from
[GitHub Releases](https://github.com/andre487/AndroidMegaProxy/releases/latest), without a GitHub
account. The app is not yet available in F-Droid.

## Choose a release APK

Open the latest release and expand **Assets**. For a first installation, choose
**[mega-proxy-universal.apk](https://github.com/andre487/AndroidMegaProxy/releases/latest/download/mega-proxy-universal.apk)**.
It includes all supported architectures. If updating, keep the same APK variant where possible.

| File | Device |
| --- | --- |
| `mega-proxy-universal.apk` | Recommended if unsure; all architectures below |
| `mega-proxy-arm64-v8a.apk` | Smaller download for 64-bit ARM devices, including most modern phones |
| `mega-proxy-armeabi-v7a.apk` | 32-bit ARM devices |
| `mega-proxy-x86_64.apk` | 64-bit x86 Android devices and emulators |
| `mega-proxy-x86.apk` | 32-bit x86 Android devices and emulators |

Choose an architecture-specific APK only if you know the device supports it. Download just one
APK: these are standalone installers, not split packages. **Source code** archives are not
installers. Older releases also contain `.aab` and native-symbol ZIP files; neither can be opened
on the phone to install the app.

## Install on a phone or tablet

1. Open the release page above on the device and download the selected APK from **Assets**.
2. Open the completed download from the browser notification or the **Downloads** file manager.
3. If Android asks to allow this source, open **Settings → Install unknown apps** and enable
   **Allow from this source** for the browser or file manager that opened the APK.
4. Return to the installer and tap **Install**, then **Open**. You can revoke that source's
   installation permission afterward.
5. Create or import a profile for your own SSH or HTTPS CONNECT server. MegaProxy does not provide
   servers or access credentials. See [server setup](https://github.com/andre487/MegaProxyServer).
6. Check the selected profile using **Test** in the main-screen menu, then tap **Connect**.
   Accept Android's system VPN connection request when prompted. This allows MegaProxy to route
   traffic through your configured proxy.

System labels vary by Android version and manufacturer. Start downloads from this project's
GitHub release page, not an APK mirror. If installation is restricted by a managed device's
administrator, contact that administrator. See [Android's installation help](https://support.google.com/android/answer/9457058).

## Check the downloaded file

Download `SHA256SUMS` from the **same release** as your APK. In a terminal opened in the download
folder, calculate the file's SHA-256 using the command for your system:

```shell
# macOS
shasum -a 256 mega-proxy-universal.apk
# Linux
sha256sum mega-proxy-universal.apk
```

```powershell
# Windows PowerShell
Get-FileHash .\mega-proxy-universal.apk -Algorithm SHA256
```

Replace the filename if you chose another variant. Compare all 64 hexadecimal characters with
that file's entry in `SHA256SUMS` (letter case does not matter). If they differ, do not install
that copy; download the APK and checksums again from the same release. Checksums detect a damaged
or changed download; they do not independently authenticate the release source.

## Install through ADB (optional)

Install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
on your computer. Enable **Developer options → USB debugging** on the phone, connect it by USB,
and accept the debugging authorization on the unlocked phone. From the APK download folder:

```shell
adb devices
adb install -r mega-proxy-universal.apk
```

The device must appear with status `device`, not `unauthorized` or `offline`. With several devices,
use `adb -s SERIAL install -r mega-proxy-universal.apk`, replacing `SERIAL` with the chosen entry
from `adb devices`. The `-r` option retains app data when replacing a compatible installation.
On Windows, use `adb.exe` from Platform Tools or add that directory to `PATH`.
Disable USB debugging after use if you no longer need it.
See the [ADB guide](https://developer.android.com/tools/adb#move).

## Update without losing settings

Check GitHub Releases for new versions and install the new APK over the existing app, using the
same steps. **Do not uninstall first:** uninstalling removes local profiles, known SSH host keys,
and settings. Export configurations before a migration; treat exported files as sensitive.

An in-place update needs the same application ID (`net.megaproxy487`), a compatible signing
certificate, and a version code that is not lower than the installed one. Official release APKs
use the project's release key. A debug build or a build from another distributor may have a
different signature and cannot necessarily be replaced in place.

Keep the same APK variant when updating. Architecture-specific APKs have slightly higher version
codes than the universal APK of the same release. Switching to universal within that release can
therefore fail as a downgrade. Use a newer release rather than forcing a downgrade or uninstalling.
After updating, check the selected profile before connecting.

## Test builds from pull requests

For a requested test, open the pull request's APK-links comment or its successful Android check.
The job summary links to artifacts; they are also listed under **Artifacts** on the workflow run.
You must be signed into GitHub with access to the repository to download them. See
[GitHub's artifact download instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).

Download `megaproxy-pr-<number>-debug-apk`, extract the ZIP, and install `app-debug.apk` using the
steps above. These artifacts expire after 14 days; skipped Android checks produce no new APK.
Use an emulator or spare device: the debug APK shares the release application ID but uses a
different signing key, so it cannot update an installed official release. Debug signatures may
also differ between CI runs. Do not remove your everyday installation merely to try a PR.

`megaproxy-pr-<number>-unsigned-release-apk` contains `app-release-unsigned.apk` for build
verification. It is not an installable signed release. For everyday use, return to GitHub Releases.

## If installation fails

| Symptom | What to check |
| --- | --- |
| App not installed / package conflicts | Existing signature and version; see the update section. Avoid uninstalling before exporting needed data. |
| Incompatible device / `INSTALL_FAILED_NO_MATCHING_ABIS` | Use universal or the correct ABI; Android 8.0 or newer is required. |
| Parsing error / invalid package | Confirm the download is an APK, not a ZIP/AAB, and compare its checksum. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Install a newer release; do not switch to a lower-code APK of the same release. |
| Not enough storage | Free device storage and retry. |
| ADB shows `unauthorized` | Unlock the phone and accept the computer's debugging authorization. |
| App installs but cannot connect | Verify the server, profile credentials, network access, and Android VPN approval; APK installation does not configure a server. |
