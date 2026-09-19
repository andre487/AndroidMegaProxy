# Fastlane workflow

MegaProxy uses [Fastlane](https://fastlane.tools/) as the supported command-line entry point for
tests and build artifacts. Gradle, Go, and the scripts in `scripts/` remain the low-level build
implementation; Fastlane gives local development and CI the same named workflows.

See the official [Fastlane Android setup guide](https://docs.fastlane.tools/getting-started/android/setup/)
and [Bundler setup instructions](https://docs.fastlane.tools/getting-started/android/setup/#use-a-gemfile)
for upstream installation details. The Gradle integration is documented in the
[Fastlane `gradle` action](https://docs.fastlane.tools/actions/gradle/).

## Installation

Install the project prerequisites listed in the root [README](../../README.md#building-from-source),
including JDK 21, Go, the Android SDK, and the Android NDK. Then install Ruby 3.4.10, as pinned in
`.ruby-version`. A Ruby version manager is recommended; do not depend on the old system Ruby
included with macOS.

Native and release build scripts discover JDK 21 from `JAVA_HOME`, macOS `java_home`, or `java` on `PATH`. An explicitly configured incompatible JDK fails early; no Homebrew installation path is assumed.

Install a current Bundler and the repository-pinned Fastlane dependency from the project root:

```shell
gem install bundler
bundle install
```

Always run Fastlane through Bundler so that `Gemfile.lock` controls the exact dependency versions:

```shell
bundle exec fastlane lanes
```

That command lists the lanes available in the checked-out version of the project.

## Supported commands

| Command | Result |
| --- | --- |
| `bundle exec fastlane android python_format` | Formats Python scripts with pinned Black and isort. |
| `bundle exec fastlane android python_tests` | Runs Python unit tests. |
| `bundle exec fastlane android python_checks` | Checks Python formatting/import order and runs unit tests. |
| `bundle exec fastlane android native_fuzz` | Fuzzes native parsers for 20 seconds with two workers. |
| `bundle exec fastlane android native_tests` | Runs all Go tests with the race detector. |
| `bundle exec fastlane android android_checks` | Builds the native AAR, runs Android unit tests and lint, builds a debug APK, then builds and verifies an unsigned release APK. It rejects any release-signing environment variables. |
| `bundle exec fastlane android test` | Runs `native_tests` and `android_checks`; this is the normal pre-commit command. |
| `bundle exec fastlane android debug_artifact` | Builds `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_artifacts` | Builds and verifies the signed release APKs, AAB, native debug symbols, and `SHA256SUMS` in `dist/release`. |
| `bundle exec fastlane android play_release` | Uploads an existing signed AAB and native symbols to Google Play; defaults to an internal draft. |

The release lane requires the signing configuration described in
[Signed release builds](../../README.md#signed-release-builds). It builds artifacts but does not
upload them to Google Play or publish a GitHub Release. GitHub Actions invokes the same lane and
handles GitHub Release publication separately.

Pull-request CI runs `android_checks`, never `release_artifacts`. It receives no signing secrets,
rejects signing configuration if one is accidentally supplied, and verifies with Android
`apksigner` that `app-release-unsigned.apk` has no signature. The separately supported
`debug_artifact` lane produces an APK signed only with the standard disposable Android debug key;
it does not use the MegaProxy release identity.

For pull requests, GitHub Actions uploads the debug and unsigned release APKs as two separately
named workflow artifacts. Direct download links are shown in the Android check's job summary, and
the files are retained for 14 days. After successful CI, a separate trusted `workflow_run` workflow
creates or updates one APK-links comment on the pull request. It does not check out, download, or
execute pull-request code or artifacts. These are test artifacts only: neither APK is signed with
the MegaProxy release key, and neither is published as a GitHub Release or sent to an app store.

## Google Play releases

`play_release` uploads an existing signed AAB and its matching native symbols for
`net.megaproxy487`. Build them with `release_artifacts` or download both from the same verified
GitHub Release. The lane does not build or sign files. Use an unused, increasing `versionCode`.
The AAB must be signed with the upload key registered in Play Console.

Before the first API upload, create the app in Play Console, configure Play App Signing and upload
an initial build manually. Enable the Google Play Developer API in a Google Cloud project, create
a service account and invite its email in Play Console with access to this app and permissions
for the intended test/production tracks. Keep the JSON key outside the repository and supply its contents through `SUPPLY_JSON_KEY_DATA`.
See [Google API setup](https://developers.google.com/android-publisher/getting_started) and
[Fastlane supply setup](https://docs.fastlane.tools/actions/upload_to_play_store/#setup).

Run from the repository root:

```shell
export SUPPLY_JSON_KEY_DATA="$(cat "$HOME/.my-tokens/megaproxy-play.json")"
bundle exec fastlane android release_artifacts
bundle exec fastlane android play_release validate_only:true
bundle exec fastlane android play_release
```

`SUPPLY_JSON_KEY_DATA` is Fastlane's standard environment variable for the complete JSON key,
not a file path or Base64 string. If your local environment already supplies it, omit the `export`
above. The file in that example is only a local storage option; the lane does not read key files.

In GitHub, create an Actions secret named `SUPPLY_JSON_KEY_DATA` containing the same complete JSON.
The existing tag-triggered release workflow passes it only to the upload step:

```yaml
- name: Upload Google Play draft
  env:
    SUPPLY_JSON_KEY_DATA: ${{ secrets.SUPPLY_JSON_KEY_DATA }}
  run: bundle exec fastlane android play_release track:internal release_status:draft
```

After building and verifying the artifacts and publishing the GitHub Release, the workflow uploads
the matching AAB and symbols from `dist/release` as an internal draft. A missing secret or failed
Play upload fails the workflow; the already published GitHub Release remains available. Do not
pass the key as a lane argument or print it in logs. PR workflows must not receive it.

For an existing local shell env file, add `export SUPPLY_JSON_KEY_DATA=...` using a shell-quoted JSON
value and source that file before running Fastlane. For example, with the local release configuration:

```shell
source "$HOME/.config/megaproxy/release.env"
bundle exec fastlane android play_release
```

Keep the env file outside the repository with permissions `0600`.

The default upload creates a **draft on the internal track**. `validate_only:true` uploads to a
temporary Google Play edit and asks the API to validate it without committing a release; it needs
credentials and network access and is not an offline dry run. Review/complete a draft in Play Console.
For a new AAB that should be released directly to testers or production, explicitly select:

```shell
bundle exec fastlane android play_release track:internal release_status:completed
bundle exec fastlane android play_release track:production release_status:completed
```

Run only the command for the intended destination. Google review, app eligibility and managed
publishing can still delay availability. To promote an already uploaded version, use Play Console;
this lane uploads a new AAB and does not promote existing releases.

| Option | Default / behavior |
| --- | --- |
| `aab` | `dist/release/mega-proxy.aab` |
| `symbols` | `dist/release/mega-proxy-native-debug-symbols.zip`; required and must match the AAB |
| `track` | `internal`; also accepts `alpha`, `beta`, `production` or a custom track ID |
| `release_status` | `draft`; supports `draft` or `completed` |
| `validate_only` | `false`; accepts only `true` or `false` |

`MEGAPROXY_RELEASE_DIR` overrides the default artifact directory. Relative file paths are resolved
from the repository root. Metadata, changelogs, images and screenshots are not uploaded; the
F-Droid listing under `fastlane/metadata/android` is left separate from Play listing management.
The tag workflow publishes a GitHub Release and a Google Play internal draft. PR CI must not
receive the Play JSON key or invoke `play_release`.

## Updating Fastlane

Update Fastlane deliberately and commit both dependency files:

```shell
bundle update fastlane
bundle exec fastlane lanes
bundle exec fastlane android test
```

Review changes to both `Gemfile` and `Gemfile.lock`. The official Fastlane documentation recommends
committing the lock file and using `bundle exec fastlane` locally and in CI.

[Русская версия](../ru/fastlane.md)

## Selective CI and Python tooling

For each suite, CI compares the current PR head with the last successful ancestor check for that
suite. Failed, cancelled and skipped jobs do not count as successful coverage. Candidates must
belong to the same PR and repository, use the same recorded PR base and precede the current run.
Rebased-away commits are ignored. The history search examines the latest 30 completed CI runs on
the branch through gh; missing history, API errors and old runs without a recorded base fall back
to the full PR diff. Every push to main runs all suites without diff/history filtering; the README badge explicitly tracks
`badge.svg?branch=main&event=push`. Each suite's baseline and decision are
shown in the Actions summary. Reruns exclude their own run ID from baseline selection.

On initial PR runs, Python-only changes run Python checks; documentation-only changes skip test jobs. Native production
changes enable Go and Android, while Go test-only changes enable Go. Shared CI/Fastlane inputs and
unknown paths enable all suites. Failed diff calculation fails `Change scope` instead of silently
skipping tests. Skipped Android builds do not publish APK artifacts.

Install the pinned development tools in a virtual environment:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
bundle exec fastlane android python_format
bundle exec fastlane android python_checks
```

`python_format` applies isort and Black. `python_tests` runs Python unit tests only;
`python_checks` checks formatting/import order and runs those tests. All three respect `PYTHON`
(default: `python3`). Runtime scripts use only Python's standard library. The CI job
`Python tests and style` runs independently of Android builds.

## Interactive GitHub Actions launcher

```sh
python3 scripts/github_actions.py
python3 scripts/github_actions.py --dry-run
python3 scripts/github_actions.py --yes
```

Choose an open PR and either rerun all CI jobs **including skipped checks**, or only failed jobs. Requires GitHub CLI (`gh`)
and its existing authentication (`gh auth login`, `GH_TOKEN` or `GITHUB_TOKEN`). The launcher uses
native gh commands, no custom HTTP client or token storage. `--repo OWNER/REPO` overrides the repo.
`--yes` / `-y` skips final confirmation but retains menu selection and the stale-head check;
`--dry-run` always prevents launching. `q` or Ctrl+C cancels. Only open same-repository PRs are listed.
The script targets an existing completed CI run for the exact current PR commit. Running/queued
jobs and missing runs are rejected; CI normally starts on pushes. Failed-only mode requires a failed
run; cancelled runs can be rerun with all jobs. Launch failures/timeouts are never retried automatically.
A full rerun also reruns Change scope. On attempt 2 or later it enables Android (including
Compose UI tests), native and Python suites without diff/history filtering. The same applies to
GitHub's Re-run all jobs button. Failed-only reruns keep the existing scope unless Change scope
itself failed and is rerun, in which case all suites are enabled.
[GitHub reruns preserve the original commit](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
Runs created before this workflow change retain the old filtering; push a new commit first.
No device or release workflows are offered.

### Native parser fuzzing

Run a bounded local fuzz campaign for native config, JA3 and DNS parsers (20 seconds, two workers). Seed inputs also run as part of `native_tests`. This campaign is not a device test.

```shell
bundle exec fastlane android native_fuzz
```

## Compose UI tests without an emulator

`bundle exec fastlane android android_checks` (and `android test`) runs the Robolectric
Compose tests in `app/src/test` together with the existing JVM tests. They also run in the
normal Android PR check; no device, ADB or KVM is required.

The interaction suite covers the main user flows:

- Main screen: connect/reconnect/disconnect, permission approval and denial, invalid profiles,
  Always-on conflicts, disabled actions while connecting, and profile selection.
- Profiles: draft creation, editing and port validation, SSH/HTTPS Jump fields, certificate
  bypass confirmation, cloning/deletion, file import errors, and export without passwords.
- Settings: traffic units, TLS fingerprint, failover confirmation, and selected-app routing.
- Navigation: settings destinations and Back, profile creation, diagnostics, and SSH prompts.
- Diagnostics: running/success/failure states, exit IP, log copying and clear confirmation.
- SSH trust: successful save, failed save and retry, disabled actions/Back during a pending
  save, and test cancellation (`SshHostKeyUiTest`).

`MainUiTestBase` uses the real screens and ConfigStore with an in-memory test Keystore
provider. Robolectric records service commands and supplies permission/document-picker
results; the connection statistics reader is injected. Tests do not start VPN forwarding,
load Go JNI or access the device Keystore. A plain test Application, Android API 35 and
English resources are pinned; Robolectric downloads its Android runtime from Maven Central
on the first run.

Add behavior tests using the same runner and Compose rule. Keep platform operations at the
screen boundary and supply deterministic fakes; avoid sleeps and real network calls.
These are interaction tests, not screenshot comparisons or device lifecycle certification.
See [Robolectric setup](https://robolectric.org/getting-started/).
