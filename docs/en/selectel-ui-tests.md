# Selectel UI tests

The `Selectel UI` workflow runs on real Android devices for same-repository PRs and manual
`workflow_dispatch` runs. Fork PRs are skipped; neither `pull_request_target` nor fork code receives
credentials. Keep this job optional in branch protection until the farm's reliability is established.
The existing native/JVM/lint checks remain required. No hosted emulator or release signing is used.

## Setup

Create a Selectel service user with `mobile_farm.admin` scoped to the chosen project. This role is
needed to add and remove paid devices, not just open an ADB session. Set these GitHub Actions secrets:
`SELECTEL_USERNAME`, `SELECTEL_PASSWORD`, `SELECTEL_ACCOUNT_ID`, `SELECTEL_PROJECT_ID`.
Use the project UUID for `SELECTEL_PROJECT_ID`, not its display name.

Locally, put the same shell variable assignments in `~/.config/megaproxy/selectel.env` (directory
mode 700, file mode 600). The wrapper loads this file only outside GitHub Actions. Override its
location with `MEGAPROXY_SELECTEL_ENV`. Do not source `release.env` for UI tests.
Python 3 and Android SDK platform-tools (ADB) are required; no Selectel CLI or pip dependencies.
Configure the normal project JDK 21/SDK/NDK/Go/Ruby toolchain before building.

```sh
bundle exec fastlane android selectel_probe
bundle exec fastlane android ui_test_artifacts
bundle exec fastlane android selectel_ui_tests
```

The first command is read-only. The last rents **one device with minute billing**. Default selection:
Android API 35, arm64, preferring Pixel if available. Override with `SELECTEL_ANDROID_API` and
optionally `SELECTEL_DEVICE_MODEL` (exact catalog name). No silent API-level fallback. Device
availability and the actual tariff depend on the provider. The UI build includes only ARM64 to reduce upload time. Build before renting to avoid paying for
compilation. ADB uses its own key directory and server port 5038 (`SELECTEL_ADB_PORT` overrides it),
so local phones and emulators are not targeted.

## Coverage and results

Instrumentation uses the real activity, navigation and profile storage with synthetic input, without
starting VPN connections. Tests cover main-menu navigation, state after activity recreation, saved
profile drafts, and closing untouched drafts, in English/Russian with 100%/200% font scale. The
original font scale is restored after each test. Existing devices in the Selectel project are untouched.

Results are in `app/build/reports/selectel/`: JUnit XML, raw instrumentation output and main-screen
screenshots. A successful ADB exit is not sufficient: missing tests, runner crashes, assertions and
timeouts fail the job. GitHub uploads reports and links them in the job summary.

## Cleanup and recovery

`.selectel/lease.json` records only project/device/slot identifiers and temporary public-key identity,
not credentials. Do not delete it while a lease is outstanding. It survives Gradle clean and is ignored
by Git. `selectel_ui_tests` cleans up in `finally`; GitHub also uses an `always()` release step.
Recovery is idempotent and verifies the slot ID so an old journal cannot delete a newer rental.

```sh
bundle exec fastlane android selectel_release
```

Closing ADB or unassigning a device does **not** end minute billing: removal from the project does.
The `Selectel lease recovery` workflow reads the uploaded lease journal after a run completes and
uses trusted default-branch code to release any remaining rental. This recovery workflow becomes
active after merging it into the default branch. It does not execute code from downloaded artifacts.
To recover manually on another checkout, download that run's `selectel-lease-*` artifact and put its
`lease.json` in `.selectel/` before invoking `selectel_release`.

There is a residual window if the runner dies before uploading the journal, or the allocation API
accepts the request but its response is lost. Allocation is deliberately not retried automatically.
An `allocation_pending` journal requires checking the Selectel project for the newly added rental
and removing it in the panel. Never remove all project devices: other rentals may belong to someone
else. The journal preserves the pre-existing device list to help investigation. API/network cleanup
errors fail the job and preserve the journal for retry. A hard kill cannot guarantee local cleanup.

References: [Mobile Farm API](https://docs.selectel.ru/api/mobile-farm/),
[project roles](https://docs.selectel.ru/mobile-farm/manage/manage-access/),
[IAM authentication](https://docs.selectel.ru/api/authorization/).
