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
| `bundle exec fastlane android renew_config_schema` | Downloads and pins the current MegaProxyConfig schemas and examples; use `ref:FULL_SHA` for a reviewed revision. |
| `bundle exec fastlane android python_format` | Formats Python scripts with pinned Black and isort. |
| `bundle exec fastlane android python_tests` | Runs Python unit tests. |
| `bundle exec fastlane android python_checks` | Checks Python formatting/import order and runs unit tests. |
| `bundle exec fastlane android native_fuzz` | Fuzzes native parsers for 20 seconds with two workers. |
| `bundle exec fastlane android native_tests` | Runs all native Go tests and local uQUIC patch regressions with the race detector. |
| `bundle exec fastlane android native_integration` | Tests production dialers against real GOST/OpenSSH servers in Docker with the race detector. |
| `bundle exec fastlane android android_checks` | Builds the native AAR, runs Android unit tests and lint, builds a debug APK, then builds and verifies an unsigned release APK. It rejects any release-signing environment variables. |
| `bundle exec fastlane android device_test_build` | Build the native AAR, debug APK and instrumentation APK. |
| `bundle exec fastlane android device_tests api:26` | Run integration scenarios on a running disposable API 26 emulator. |
| `bundle exec fastlane android device_tests api:35` | Run integration scenarios on a running disposable API 35 emulator. |
| `bundle exec fastlane android test` | Runs `native_tests` and `android_checks`; this is the normal pre-commit command; excludes Python, Docker integration and emulator tests. |
| `bundle exec fastlane android debug_artifact` | Builds `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_prepare version:0.1.2` | Generates EN/RU notes, increments the version and creates a release PR (requires API/token setup). |
| `bundle exec fastlane android release_finish version:0.1.2 pr:123 head:FULL_SHA` | Requires full CI, squash merges the specified PR head and tags the merged commit. |
| `bundle exec fastlane android virus_total` | Scan signed APKs in `dist/release` (or `artifacts:PATH`); requires `VIRUSTOTAL_API_KEY`, blocks on detections/API failures. |
| `bundle exec fastlane android release_artifacts` | Builds and verifies the signed release APKs and `SHA256SUMS` in `dist/release`. |

The release lane requires the signing configuration described in
[Signed release builds](../../README.md#signed-release-builds). It builds artifacts but does not
publish a GitHub Release. GitHub Actions invokes the same lane and
handles GitHub Release publication separately.

Pull-request CI runs `android_checks`, never `release_artifacts`. It receives no signing secrets,
rejects signing configuration if one is accidentally supplied, and verifies with Android
`apksigner` that `app-release-unsigned.apk` has no signature. The separately supported
`debug_artifact` lane produces an APK signed only with the standard disposable Android debug key;
it does not use the MegaProxy release identity.

For pull requests, GitHub Actions uploads the debug and unsigned release APKs as two separately
named workflow artifacts. Direct download links are shown in the Android check's job summary, and
the files are retained for 14 days. After successful CI, a separate trusted `workflow_run` workflow
creates or updates an APK-links block at the bottom of the pull request description, preserving
the author's text. Stale runs cannot overwrite links from newer runs. Skipped Android builds retain
the previous links, labelled with their original commit. It does not check out, download, or
execute pull-request code or artifacts. These are test artifacts only: neither APK is signed with
the MegaProxy release key, and neither is published as a GitHub Release or sent to an app store.

The release lane builds one native AAR containing all four ABIs, then reuses it
for the four architecture-specific APKs and the universal APK. Gradle's ABI filters
select native libraries; intermediate outputs are retained between APKs. Version
codes and `APK_VARIANT` remain variant-specific, so some compilation and R8 work
still repeats. The universal AAR uses the same binding flags as the F-Droid recipe.

## Real proxy server tests

Run `bundle exec fastlane android native_integration` with a running local Docker
Engine (Linux or a Docker VM on macOS). Compose, a public server, Android SDK,
emulator, system SSH configuration and production credentials are not needed.
The first run downloads GOST 3.3.0 (pinned image digest) and builds an Alpine fixture
with OpenSSH and Python. Registry/package access is required for this setup.

The lane creates a separate Docker network, three GOST proxies (HTTPS/HTTP2/MASQUE), two
OpenSSH servers with temporary host/client keys, and an HTTP/UDP echo server with no
published port. Proxy ports are allocated dynamically on host loopback only.
Production configuration parsing and dialers send three different binary POST
payloads through HTTPS, HTTP2, HTTPS Jump, MASQUE, SSH password, SSH private key, and SSH
Jump. Responses must match byte for byte. Negative cases reject incorrect
credentials and TLS/SSH trust. The origin hostname must not be reachable directly
from the test host.

MASQUE checks Chrome, Firefox and custom JA3 fingerprints, TCP, UDP, Basic authentication and certificate verification. The fixture uses `masque+http3`; GOST `h3` is a different transport. MASQUE needs access to the published Docker UDP port. If a macOS VM does not forward UDP, run the test inside the Linux VM. API 26/35 Android scenarios include MASQUE TCP/UDP and stop/reconnect.

The native CI job runs this lane after ordinary Go tests. Missing Docker is an
error, not a skipped success. `native_tests` and the regular local `test` lane do
not require Docker; integration tests have a separate Go build tag. Tests remove
their containers, network and temporary image on success or failure and print
container logs on failure. A forcibly killed process may need manual cleanup of
its `megaproxy-test-*` resources. Docker's downloaded image/build cache is retained.

These checks cover real server interoperability and tunneled HTTP payloads. They
do not exercise Android TUN/JNI, VpnService lifecycle, device routing or DoH.
GOST's generated self-signed certificates are explicitly allowed in successful
fixture connections; separate rejection cases keep verification enabled at each hop.
OpenSSH host fingerprints are pinned to the keys generated inside the containers.

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

The artifact-workflow tests also require Node.js on PATH to execute the trusted JavaScript
with mocked GitHub APIs; GitHub-hosted CI runners provide it.

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

## JUnit reports in GitHub Checks

`native_tests` and `native_integration` use pinned gotestsum v1.13.0 and write JUnit XML to
`test-results/native/unit.xml` and `test-results/native/integration.xml`. The first run downloads
the runner through Go without changing the app module. `python_tests` uses pinned xmlrunner from
`requirements-dev.txt` and writes XML to `test-results/python/`. Android JVM tests already write
JUnit XML under `app/build/test-results/testDebugUnitTest/`; device tests keep separate API 26/35
reports. Test failures still return a nonzero exit status.

CI uploads each suite's XML as a separate `junit-*` artifact even when tests fail, with seven-day
retention. Each suite has a separate publisher that starts as soon as that suite finishes, without waiting
for the rest of CI. It creates `Test results · …` GitHub Checks with test counts and failure
details. Actions Summary also provides collapsible groups with all test names and statuses. Skipped suites have no report and are not presented as
newly passed tests. Existing required checks continue to gate merges.

The publisher has `checks: write`; test jobs retain read-only repository access. Internal PRs and
pushes publish independently after each corresponding test job finishes. Fork PRs publish from the trusted `workflow_run` workflow,
which is already present in `main`. It reads artifacts without
checking out or executing PR code. Only JUnit artifacts are parsed; device UI hierarchy XML stays
in the separate evidence artifact.

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

[Release workflow setup and recovery](release-automation.md).

Release branches `release/vX.Y.Z` always run all CI suites; skipped jobs cannot authorize release finalization.

## Android emulator integration tests

CI runs API 26 and API 35 as independent jobs with independent successful-history baselines.
Both use Ubuntu 24.04, explicit KVM permissions and mandatory hardware acceleration. Both APIs
cold boot without snapshot caches to avoid the failures observed after snapshot restore:
pre-test input failures on API 26 and ADB disconnects during diagnostics on API 35. No software fallback
or automatic test retry is used. Emulator startup gets one retry only if the test runner has
not started; a runner-started marker prevents retrying any test/setup failure inside the runner.
SDK installation also gets one retry for transient download failures.
Both startup attempts failing keeps the required check red. Main pushes and full reruns execute both scenarios.

Locally, start a **disposable** Google APIs emulator with English system UI and set
`ANDROID_SERIAL=emulator-5554` (use its actual serial), then run:

```shell
bundle exec fastlane android device_test_build
bundle exec fastlane android device_tests api:35
```

Use `api:26` for a separate API 26 emulator. Build the native AAR for the emulator ABI; CI sets
`MEGAPROXY_ANDROID_ABI=x86_64`, while the default local build includes all ABIs. Docker must be
running. On Linux the fixture requires passwordless `sudo iptables` to reject direct host/emulator
connections to the unpublished origin; the disposable rule is removed on exit. Docker VM origins
are inaccessible directly on macOS. Every network scenario verifies that direct access fails.
The runner clears **MegaProxy app data on the selected emulator**, uses disposable passwords and
SSH keys, and force-stops the app between scenarios. Do not point it at a development emulator
with data you want to keep. It rejects physical devices and API mismatches.

Coverage: real HTTPS proxy traffic through TUN/JNI, stop/restart, stop while real JNI awaits an
SSH handshake, denied VPN consent, notification Disconnect, notification permission denial on
API 35, encrypted password/key persistence across a fresh app process and real PASSWORD_ONLY/KEY_ONLY OpenSSH
traffic, and system DocumentsUI export/import/cancellation. Exports omit passwords by default.
System picker tests exercise the actual Downloads provider and returned content URI.

The process test has separate seed/verify instrumentation invocations with an explicit force-stop
between them; activity recreation alone cannot pass it. All configuration checks drain the
ordered executor and assert both completion and absence of write failure. Network tests use an
unpublished HTTP echo origin and assert the response marker and exact payload, not only CONNECTED.

JUnit XML, instrumentation output and Logcat are saved under `test-results/android-api26/` or
`test-results/android-api35/`; failures also capture a screenshot and UI hierarchy. CI uploads
separate seven-day artifacts and links them in each job summary. Pure logic, most Compose
interactions and the full transport failure matrix remain in JVM/Robolectric/Go tests.
These emulators do not certify OEM behavior, physical network handover or all Always-on modes.

## Portable configuration schemas

`renew_config_schema` uses authenticated `gh` and Python's standard library to resolve one
MegaProxyConfig commit, download both draft 2020-12 schemas, examples and LICENSE, and record
SHA-256 checksums in `config-schema/schema-lock.json`. Review and commit the whole directory.
CI reads only these committed files; it never fetches a moving upstream branch.
`android_checks` validates real exports against both schemas and checks examples, enums and
checksums. The JVM validator is a test dependency only. Import remains compatible with versions
1–8; it uses the bundled shared schema to recognize fields, without rejecting permissive legacy input.

The HTTPS profile preference is also tested through real TUN/JNI: HTTP/3 selection with UDP, HTTPS fallback and warning reset on disconnect. HTTPS Jump additionally covers all four combinations of HTTP/3 support on the two hops: nested TCP/UDP when both support it, and whole-chain HTTPS fallback when either or both do not.
