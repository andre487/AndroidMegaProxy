# Fastlane workflow

Install JDK 21, Go and the Android SDK/NDK from [README](../../README.md#building-from-source),
Ruby 3.4.10 (`.ruby-version`), then `bundle install`. Always use `bundle exec` with `Gemfile.lock`.
Native/release scripts discover JDK 21 without a machine-specific path; an incompatible
explicit `JAVA_HOME` fails early. `bundle exec fastlane lanes` lists the checked-out commands.

## Commands

| Command | Result |
| --- | --- |
| `bundle exec fastlane android renew_config_schema` | Downloads and pins the current MegaProxyConfig schemas and examples; use `ref:FULL_SHA` for a reviewed revision. |
| `bundle exec fastlane android python_format` | Formats Python scripts with pinned Black and isort. |
| `bundle exec fastlane android python_tests` | Runs Python unit tests. |
| `bundle exec fastlane android python_checks` | Checks Python formatting/import order and runs unit tests. |
| `bundle exec fastlane android native_fuzz` | Fuzzes native parsers for 20 seconds with two workers. |
| `bundle exec fastlane android native_tests` | Runs all native Go tests and local uQUIC patch regressions with the race detector. |
| `bundle exec fastlane android native_integration` | Tests production dialers against real GOST/OpenSSH servers in Docker with the race detector. |
| `bundle exec fastlane android android_checks` | Builds the native AAR, resolves pinned Robolectric SDKs through Gradle, runs Android unit tests and lint, builds a debug APK, then builds and verifies an unsigned release APK. It rejects any release-signing environment variables. |
| `bundle exec fastlane android device_test_build` | Build the native AAR, debug APK and instrumentation APK. |
| `bundle exec fastlane android device_tests api:26` | Run integration scenarios on a running disposable API 26 emulator. |
| `bundle exec fastlane android device_tests api:35` | Run integration scenarios on a running disposable API 35 emulator. |
| `bundle exec fastlane android test` | Runs `native_tests` and `android_checks`; excludes Python, Docker integration and emulator tests. |
| `bundle exec fastlane android debug_artifact` | Builds `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_prepare version:0.1.2` | Generates EN/RU notes, increments the version and creates a release PR (requires API/token setup). |
| `bundle exec fastlane android release_notes_preview version:1.1.3 ref:v1.1.3` | Previews concise EN/RU GitHub and F-Droid notes for an explicit Git range without publishing. |
| `bundle exec fastlane android release_finish version:0.1.2 pr:123 head:FULL_SHA` | Requires full CI, merges the specified PR head with a merge commit and tags the merged commit. |
| `bundle exec fastlane android virus_total` | Scan signed APKs in `dist/release` (or `artifacts:PATH`); requires `VIRUSTOTAL_API_KEY`, blocks on detections/API failures. |
| `bundle exec fastlane android release_artifacts` | Builds and verifies the signed release APKs and `SHA256SUMS` in `dist/release`. |

## Choosing checks and reading results

The [testing policy](release-testing.md) defines local, CI and release checks and their limits.
`test` combines only native unit/race tests and Android JVM/lint/debug/unsigned-release checks;
Python, Docker integration and device tests are separate lanes.

JUnit locations: `test-results/native/{unit,integration}.xml`, `test-results/python/`,
`app/build/test-results/testDebugUnitTest/` and `test-results/android-api{26,35}/`.
CI uploads seven-day `junit-*` artifacts and publishes each suite as soon as it finishes.
Skipped suites do not count as new passes. Fork reports parse artifacts in a trusted
`workflow_run` without checking out or executing PR code. Original suite checks gate merging.
PR debug/unsigned APK links appear in the job summary and PR description; they expire in 14 days
and never use release signing secrets. [Installation](installation.md) explains their use.

## Local emulator integration

Use a disposable Google APIs emulator with English system UI and matching API, Docker and
`ANDROID_SERIAL=emulator-5554` (replace with its actual serial). **The runner clears MegaProxy data.**
It rejects physical devices. Linux fixtures need passwordless `sudo iptables` to block direct
origin access; Docker VM origins are already unreachable directly on macOS.

```sh
# Build once for the emulator ABI; default builds include every ABI.
MEGAPROXY_ANDROID_ABI=x86_64 bundle exec fastlane android device_test_build
bundle exec fastlane android device_tests api:35
```

Repeat `device_tests api:26` on a separate API 26 emulator. Cold boot with acceleration;
unset `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY` and their lowercase variants when
launching any local emulator. No snapshot restores, software emulation or test retries.
Reports, per-case instrumentation output and Logcat go to the API-specific results directory;
failures include screenshot/UI hierarchy when available.

## Python and launcher

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
bundle exec fastlane android python_checks
```

`python_format` applies pinned Black/isort; all Python lanes respect `PYTHON` (default `python3`).
Runtime scripts use stdlib and native `gh`. Workflow-JavaScript tests also need Node.js.
`python3 scripts/github_actions.py` selects an open PR and reruns full CI or failed jobs;
`--dry-run` previews, `--yes` skips confirmation, `--repo OWNER/REPO` selects a repository.
It requires authenticated `gh`, a completed run for the current PR head, and rechecks that head.

## Dependencies and releases

`native_integration` needs Docker and registry/package access to create disposable GOST/OpenSSH
fixtures with private HTTP/UDP origins; no personal servers or credentials are used.
It tests production dialers, payloads, authentication and trust, not Android routing/lifecycle.
Containers/networks are removed on exit; forcibly killed runs may need `megaproxy-test-*` cleanup.
`native_fuzz` runs parser campaigns for 20 seconds/two workers; seeds also run in `native_tests`.

`renew_config_schema [ref:FULL_SHA]` fetches MegaProxyConfig through authenticated `gh`; commit
schemas, examples, license and SHA-256 lock together. CI only reads the vendored snapshot.
Legacy import remains permissive; explicit unsupported transports fail. Update Fastlane with
`bundle update fastlane`, review both Gemfiles, list lanes and run affected checks.

Signed artifacts, API/token setup, publication and recovery: [release automation](release-automation.md).
`release_finish` merges and tags; it is not a dry run. PR checks never sign releases or submit to VirusTotal.

[Русская версия](../ru/fastlane.md)
