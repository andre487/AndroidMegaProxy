# App update device review

Review snapshot: October 2, 2026. Feature source: `c37dee4` (PR #43).
This records local device checks, not F-Droid approval or a full VPN regression run.

## Devices and real installation results

| Device | Android / API | Installed APK variant | GitHub update through Android installer |
| --- | --- | --- | --- |
| OnePlus 7 Pro (GM1910) | 12 / 31 | arm64-v8a | Passed, installed version code 14002 |
| OnePlus Open (CPH2551) | 16 / 36 | arm64-v8a | Passed, installed version code 14002 |
| Existing review emulator | 15 / 35 | universal | Passed, installed version code 14000 |
| MegaProxy_Review_API_26 emulator | 8.0 / 26 | universal | Passed, installed version code 14000 |

API 26 is the declared `minSdk`. Its emulator uses the official Google APIs ARM64
system image, revision 3, with hardware virtualization on Apple Silicon. This is a
local test environment; no emulator requirement was added to GitHub Actions.

To exercise a real available update without publishing a release, the feature was
built in an isolated temporary worktree with version name 0.1.0 and base code 13.
The devices downloaded the actual published GitHub v0.1.1 APKs, verified them, and
installed them through the system confirmation dialog. Production version fields
and ABI offsets were not changed. Both builds used the existing release signer.

The published v0.1.1 does not contain this new updater. After the installation
checks, the current feature build (0.1.1, base code 14) was reinstalled on every
test device without clearing its data. Installed APK hashes were compared with
the corresponding locally built feature APKs. Neither physical phone was
uninstalled. The Android 16 phone's existing VPN returned to connected state
after the real update; this does not establish full tunnel or reconnect coverage.

## Scenarios exercised

- **Source selection:** unknown installer offered a choice; a reported non-F-Droid
  installer selected GitHub. On Android 16, reinstalling the test APK with the
  official F-Droid installer identity selected F-Droid. This simulated installer
  metadata; the APK was not installed from the F-Droid repository.
- **F-Droid API:** Android 16 and API 26 queried the real per-app endpoint. The
  unpublished package produced the expected unavailable message and retained
  F-Droid as the source, without silently switching to GitHub.
- **GitHub download:** manual checks found v0.1.1; explicit consent preceded
  download. The consent dialog's cancellation path was also exercised on API 26.
  Download verification and Android installation succeeded on all four devices.
  Universal installations stayed universal; ARM64 installations stayed ARM64.
- **Install permission:** each installer flow worked through Android's unknown-app
  source permission page, returning to MegaProxy and tapping Install again.
  The temporary permission was switched off again after testing on both phones.
- **Notifications:** forced JobScheduler checks posted a real update notification
  with Update, Skip this version, and Disable auto-checks actions. Android 15's
  notification permission was enabled through the app's system-settings link.
  Update was tapped on Android 15 and the app's update screen was inspected.
- **Skip:** the actual notification action on Android 16 removed the notification;
  another forced check did not post it again. Manual checking still offered it.
- **Disable:** the actual notification action on API 26 removed the scheduled job.
  Autochecks were re-enabled after the test.
- **Reminder:** on API 35, dismissing the notification and forcing another check
  did not immediately repost it. With the app stopped, only its saved
  `notified_at` value was backdated eight days; the next job posted a new
  notification and updated that timestamp. System time was not changed.
- **Current version:** after restoring the current build, API 26 reported no
  newer GitHub version. Its source was returned to GitHub after the F-Droid check.

## Automated checks and limits

`bundle exec fastlane android android_checks` passed: 164 JVM/Compose tests,
zero failures/errors/skips, Android lint, debug/release builds and the unsigned
CI release check. The two local signed artifact builds also completed through
`bundle exec fastlane android release_artifacts` in the temporary worktree.

Jobs were forced locally: this verifies the job's work and preferences, not actual
24-hour scheduling under Doze or manufacturer battery restrictions. The weekly
reminder used timestamp simulation, not an eight-day wall-clock wait. F-Droid has
no published package yet, so its positive update/installation path remains
untested against a real repository release. Wrong-signer, malformed metadata,
and incompatible APK cases rely on automated tests rather than destructive
phone experiments. The universal and ARM64 artifacts were exercised; the other
ABI-specific artifacts were not device-tested in this run.

OnePlus 7 Pro repeatedly slept and ignored ADB wake/scroll input; user-assisted
unlocking was necessary. Its existing battery-optimization prompt also obstructed
the screen and was dismissed by closing the system Settings activity. This is an
OEM/manual-test limitation and a follow-up for onboarding, not evidence that
background delivery is reliable on that device. No updater code change was
needed for the successful installation scenarios above.
