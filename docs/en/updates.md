# App updates

[Русский](../ru/updates.md)

Open **Settings → App updates** to check manually, choose the update source, or disable automatic
checks. Automatic checking is enabled by default; automatic APK downloading and silent installation
are not. These features apply to releases containing this update mechanism, not older installed APKs.

## How the source is selected

An explicit selection in settings is saved and takes priority. Otherwise MegaProxy asks Android
which package installed it:

| Installer reported by Android | Update source |
| --- | --- |
| Official F-Droid (`org.fdroid.fdroid`) or its privileged installer | F-Droid |
| Another installer that handles `fdroidrepo://` or `fdroidrepos://` links | F-Droid |
| Another named installer without those handlers, including a browser or file manager | GitHub |
| Missing, empty, or unreadable installer information, or failed handler lookup | Ask the user to choose; no background requests until then |

Repository-link support is checked only in the installer package; an unrelated installed F-Droid
client does not change the source. This is a capability heuristic, not proof of the APK's origin.
Clients without these handlers need manual source selection. The installer identifies an app,
not the original download website. Installing an APK downloaded
from the F-Droid website through a browser also selects GitHub initially. This is a default choice,
not proof of the APK's origin. Source changes do not bypass signature checks.

## Automatic checks and notifications

Android schedules a network-only check about once per day, including after device restarts.
Battery restrictions, Doze, no network, force-stop, or disabled background work can delay checks;
these are not exact alarms. Nothing is downloaded except release metadata during a check.
If the source is unknown, choose it in settings before automatic checks can start.

A new version produces a notification, subject to Android's notification permission and the
**App updates** notification channel. On Android 13 or newer, allow notifications when prompted,
or use the notification permission button on the update screen.

The notification offers:

- **Update**: open the update screen and check the selected source again. F-Droid updates open
  the package web link in a compatible client or browser; GitHub updates require explicit download consent.
- **Skip this version**: suppress automatic notifications for this version in this source.
  Later versions can still notify. Manual checks still show a skipped update.
- **Disable auto-checks**: stop scheduled checks and remove the notification. Manual checks remain
  available; re-enable the switch in settings to resume.

Ignoring or dismissing a notification does not skip the version. The next successful scheduled
check at least seven days after the previous notification can remind you again. A newer version
can notify sooner. Reminder and skip state survive process/device restarts; they are cleared with
app data. A disabled notification permission/channel prevents reminders but does not disable checks.

## F-Droid

MegaProxy queries the official per-app API and compares its **suggested**, published version code
with the installed APK. It does not choose a higher experimental version just because one exists.
Installation is handled by F-Droid, including package selection and verification.

MegaProxy is not yet published in F-Droid. A missing listing is displayed as unavailable, not as
an up-to-date result. Network/API failures never silently switch the source to GitHub.

## GitHub

MegaProxy checks the latest stable GitHub Release and preserves the installed APK variant:
`universal`, `arm64-v8a`, `armeabi-v7a`, `x86`, or `x86_64`. The variant is recorded at build time;
it is not inferred from the phone's CPU. If that release lacks the matching APK, the check fails
instead of silently choosing another variant. Existing version codes are unchanged.

1. Choose **Download from GitHub** and read the consent dialog. It explains that this source
   bypasses F-Droid's checks. Canceling downloads nothing and leaves the installed app unchanged.
2. After confirmation, MegaProxy downloads the selected release asset over HTTPS into private
   cache. It checks size and SHA-256 against GitHub's asset metadata, package ID, release version,
   a higher version code with the same variant offset, and signing certificates matching the
   installed app. A build signed with a different key (including a debug build) cannot be replaced.
3. Tap **Install update**. If prompted, allow installations from MegaProxy, return to the update
   screen and tap again. Android's system installer verifies the APK and asks you to confirm.
   You can revoke the installation permission afterward.

Downloads survive screen rotation while the update screen remains in the navigation stack.
Leaving it can cancel the operation. Process death loses the pending installation state; check and
download again. Cached APKs are temporary and may be cleared by Android or by clearing app data.
No failed check or download uninstalls or replaces the working app. Do not uninstall to resolve
signature or downgrade errors: that would remove local profiles and settings.

## Network and implementation details

Only the selected source is queried; profiles, passwords, browsing destinations, and app inventories
are not sent. Providers see the request and its source IP under their own privacy policies.

- F-Droid metadata: `https://f-droid.org/api/v1/packages/net.megaproxy487`.
- GitHub metadata: `https://api.github.com/repos/andre487/AndroidMegaProxy/releases/latest`.
- Consented APK downloads: the matching version-specific asset under
  `https://github.com/andre487/AndroidMegaProxy/releases/download/`, with HTTPS redirects to GitHub's
  release asset CDN (`release-assets.githubusercontent.com`).

Checks use Android's normal networking and follow applicable VPN routing. They are not sent through
an extra application-specific proxy connection. Metadata responses and APK downloads have size
limits and connection/read timeouts. No API key, account, or new dependency is required.

References: [F-Droid API](https://f-droid.org/docs/All_our_APIs/),
[F-Droid consent rules](https://f-droid.org/en/docs/Inclusion_Policy/),
[Android installer information](https://developer.android.com/reference/android/content/pm/InstallSourceInfo).
