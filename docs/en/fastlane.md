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
| `bundle exec fastlane android native_tests` | Runs all Go tests with the race detector. |
| `bundle exec fastlane android android_checks` | Builds the native AAR, runs Android unit tests and lint, builds a debug APK, then builds and verifies an unsigned release APK. It rejects any release-signing environment variables. |
| `bundle exec fastlane android test` | Runs `native_tests` and `android_checks`; this is the normal pre-commit command. |
| `bundle exec fastlane android debug_artifact` | Builds `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_artifacts` | Builds and verifies the signed release APKs, AAB, native debug symbols, and `SHA256SUMS` in `dist/release`. |

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
to the full PR diff. Pushes to main compare push endpoints. Each suite's baseline and decision are
shown in the Actions summary. Reruns exclude their own run ID from baseline selection.

Python-only changes run Python checks; documentation-only changes skip test jobs. Native production
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

Choose an open PR and either rerun all CI jobs or only failed jobs. Requires GitHub CLI (`gh`)
and its existing authentication (`gh auth login`, `GH_TOKEN` or `GITHUB_TOKEN`). The launcher uses
native gh commands, no custom HTTP client or token storage. `--repo OWNER/REPO` overrides the repo.
`--yes` / `-y` skips final confirmation but retains menu selection and the stale-head check;
`--dry-run` always prevents launching. `q` or Ctrl+C cancels. Only open same-repository PRs are listed.
The script targets an existing completed CI run for the exact current PR commit. Running/queued
jobs and missing runs are rejected; CI normally starts on pushes. Failed-only mode requires a failed
run; cancelled runs can be rerun with all jobs. Launch failures/timeouts are never retried automatically.
Rerunning preserves that run's original commit and diff baseline; push a new commit to reassess scope
against an updated PR base. No device or release workflows are offered.
