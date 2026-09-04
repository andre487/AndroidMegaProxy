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
named workflow artifacts. They can be downloaded from the PR check run for 14 days. These are test
artifacts only: neither APK is signed with the MegaProxy release key, and neither is published as a
GitHub Release or sent to an app store.

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
