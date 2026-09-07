# Selectel UI tests

The `Selectel UI` workflow runs **manually**, on the PR branch selected in Actions → Selectel UI →
Run workflow. Pushes do not rent devices. Choose one independent profile:

- `required`: HONOR X8c / Android 15 (API 35), 12 test cases. Its `Selectel UI required` check must
  succeed for the current PR commit before merging; a new commit requires a new manual run when the full PR diff affects Android.
- `additional`: Galaxy A03 / Android 11, Galaxy A14 / Android 13, Galaxy A35 / Android 16,
  Pixel 8 / Android 17 — four devices, 48 test cases. Its `Selectel UI additional` check is optional.
  It excludes the required device configuration and cannot satisfy or overwrite the required check.

The fixed snapshot is `config/selectel-devices.json`, captured on 2026-09-07. Both profiles use ARM64.
Additional devices run at most two simultaneously in GitHub; locally they run sequentially. There is
no runtime discovery or substitution if a configuration is unavailable. Update the snapshot in a PR.
Each profile fails if its build, any selected device or lease cleanup fails. Native/Python/Android
checks run automatically when relevant to the full PR diff. The two UI profiles are launched separately; additional is not needed
for the merge gate.

```sh
gh workflow run selectel-ui.yml --ref YOUR_PR_BRANCH -f devices=required
# Or: -f devices=additional
```

Only repository branches can be selected; fork code is not automatically tested with secrets.
No hosted emulator or release signing is used. The ruleset for `main` requires `Selectel UI required`
from GitHub Actions alongside the existing native/Android checks. Workflow dispatch becomes available
in the Actions UI after the workflow is merged into the default branch.

The UI results are published as commit statuses, not only manual-workflow check runs. On each PR
commit affecting Android, CI initializes `Selectel UI required` as pending with a link to the manual workflow. Starting
and finishing a UI profile updates its own status on the tested SHA. Rerunning CI preserves existing
UI results. A new commit never inherits a previous commit's UI success.

CI classifies the **full PR diff against its base**, not only the latest push. Python-only changes
run Python checks; documentation-only changes skip test jobs. Android and UI tests are skipped when
Android is unaffected, and the required UI status succeeds with an explicit skip reason. Manual UI
dispatch uses the same filter and does not build APKs or rent devices in that case. Shared CI/build
inputs and unknown paths conservatively enable all suites. `Change scope` is a required check.

## Interactive GitHub launcher

```sh
python3 scripts/github_actions.py
# Preview the selection without starting jobs or renting devices:
python3 scripts/github_actions.py --dry-run
```

Choose an open PR and then UI `required`, UI `additional`, or a rerun of ordinary CI. The launcher shows the
branch inputs and current commit, asks before sending the request, and refuses to proceed if the PR
changed during selection. UI dispatch resolves the branch head when GitHub accepts it; avoid pushing
while launching. CI reruns target only an existing, completed run for the current PR commit. Fork PRs
are excluded. Release workflows are not offered. `q` or Ctrl+C cancels.

`--yes` / `-y` skips the final confirmation while retaining PR/profile selection and the check
that the PR head has not changed. `--dry-run` still prevents launches when combined with `--yes`.

The Python code uses only the standard library and invokes **GitHub CLI (`gh`)**. Install `gh`, then
run `gh auth login` once (or use its existing `GH_TOKEN`/`GITHUB_TOKEN` environment authentication).
The script has no HTTP client or token storage. It calls `gh pr list/view`, `gh workflow run`, and
`gh run list/rerun` with argument arrays, without a shell. No local Selectel secrets or Android
toolchain are needed. Use `--repo OWNER/REPO` to override the repository. The launcher lists up to
1,000 open PRs. Failed or timed-out launches are not retried: check Actions before trying again.
The launcher works for our registered workflow before its web Run workflow button appears.

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
HONOR X8c, Android API 35, arm64. Override with `SELECTEL_ANDROID_API` and
`SELECTEL_DEVICE_MODEL` (exact catalog name). No silent model/API-level fallback. Device
availability and the actual tariff depend on the provider. The UI build includes only ARM64 to reduce upload time. Build before renting to avoid paying for
compilation. ADB uses its own key directory and server port 5038 (`SELECTEL_ADB_PORT` overrides it),
so local phones and emulators are not targeted.

Before creating a rental, the runner waits up to **600 seconds**, polling free-device availability
every 30 seconds. `SELECTEL_DEVICE_WAIT_SECONDS` accepts 0–900 seconds (0 means one immediate check).
This wait creates no rental and incurs no device billing. API errors fail immediately; an ambiguous
rental POST is never retried. A device may still be taken between the availability check and rental.
The device job has a 40-minute limit to leave time for tests and cleanup after waiting.

The runner follows the [current Selectel API](https://docs.selectel.ru/api/mobile-farm/):
register a public ADB key, assign the device with `POST /v3/users/devices`, then open
`POST /v3/users/devices/{serial}/remote-connect`. Assignment accepts an empty successful response;
remote connect supplies `remoteConnectUrl` without a `success` field. Check operational status 3,
`ready` and `present` before and after assignment. This numeric status is separate from ownership.
Following the [ADB instructions](https://docs.selectel.ru/mobile-farm/manage/connect-to-device-with-adb/),
connect once, then poll `adb devices -l` for the exact endpoint to reach `device` (90 seconds maximum).
An authentication warning from `connect` alone does not fail the run; intermediate `offline` states
do not trigger repeated disconnects. Close remote connect and assignment through the matching v3
DELETE methods, then remove the paid device with its slot ID. Session cleanup errors are reported
without preventing rental removal. Existing lease journals remain compatible with recovery.

## Local additional devices

```sh
bundle exec fastlane android ui_test_artifacts profile:additional
bundle exec fastlane android selectel_ui_tests profile:additional
```

Use `profile:additional` for both commands. The selected profiles use ARM64; if ARM32 is explicitly added
to the snapshot later, the build switches to a universal APK. Locally,
devices run sequentially with independent lease journals and report directories named by catalog ID.
Test failures do not stop remaining configurations; cleanup failures stop new rentals immediately.
The summary is `app/build/reports/selectel/matrix.json`. `selectel_release` retries all journals
under `.selectel/`, including journals downloaded into separate artifact subdirectories.

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


Cancellation uses the device job's `always()` release step and a separate `workflow_run: completed`
recovery run, including cancelled conclusions. Recovery also closes a failed/cancelled commit status
without overwriting a newer run. Normal cancellation has a finite cleanup window (GitHub can force
termination after five minutes); force-cancel and a dead runner can bypass local cleanup. Recovery
needs the uploaded journal and only becomes active once this workflow reaches the default branch.

There is a residual window if the runner dies before uploading the journal, or the allocation API
accepts the request but its response is lost. Allocation is deliberately not retried automatically.
An `allocation_pending` journal requires checking the Selectel project for the newly added rental
and removing it in the panel. Never remove all project devices: other rentals may belong to someone
else. The journal preserves the pre-existing device list to help investigation. API/network cleanup
errors fail the job and preserve the journal for retry. A hard kill cannot guarantee local cleanup.

References: [Mobile Farm API](https://docs.selectel.ru/api/mobile-farm/),
[project roles](https://docs.selectel.ru/mobile-farm/manage/manage-access/),
[IAM authentication](https://docs.selectel.ru/api/authorization/).
