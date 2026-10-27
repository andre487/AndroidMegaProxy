fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android renew_config_schema

```sh
[bundle exec] fastlane android renew_config_schema
```

Explicitly update pinned MegaProxyConfig schemas and examples

### android update_graphics

```sh
[bundle exec] fastlane android update_graphics
```

Regenerate committed artwork from MegaProxyIdentity SVGs

### android native_tests

```sh
[bundle exec] fastlane android native_tests
```

Run the native Go tests with the race detector

### android native_benchmarks

```sh
[bundle exec] fastlane android native_benchmarks
```

Benchmark the native core locally; save samples and optional CPU/heap profiles

### android native_integration

```sh
[bundle exec] fastlane android native_integration
```

Test production dialers against disposable GOST and OpenSSH servers (requires Docker)

### android native_fuzz

```sh
[bundle exec] fastlane android native_fuzz
```

Fuzz native config, JA3 and DNS parsers for 20 seconds with two workers

### android android_checks

```sh
[bundle exec] fastlane android android_checks
```

Run Android checks, build a debug APK, and verify that the CI release APK is unsigned

### android virus_total

```sh
[bundle exec] fastlane android virus_total
```

Scan release APKs with VirusTotal before publication

### android device_test_build

```sh
[bundle exec] fastlane android device_test_build
```

Build debug and instrumentation APKs for a disposable emulator

### android device_tests

```sh
[bundle exec] fastlane android device_tests
```

Run real VPN, Keystore and document-provider tests on a disposable API 26 or 35 emulator

### android test

```sh
[bundle exec] fastlane android test
```

Run every native and Android test and check

### android python_tests

```sh
[bundle exec] fastlane android python_tests
```

Run Python unit tests

### android python_format

```sh
[bundle exec] fastlane android python_format
```

Format Python scripts with isort and Black

### android python_checks

```sh
[bundle exec] fastlane android python_checks
```

Check Python formatting, import order, and unit tests

### android debug_artifact

```sh
[bundle exec] fastlane android debug_artifact
```

Build a debug APK after preparing the native library

### android release_prepare

```sh
[bundle exec] fastlane android release_prepare
```

Generate EN/RU release notes, bump the version, and create a release PR

### android release_notes_preview

```sh
[bundle exec] fastlane android release_notes_preview
```

Preview EN/RU changelogs for an explicit Git range without changing release files

### android release_finish

```sh
[bundle exec] fastlane android release_finish
```

Wait for full release PR CI, create a merge commit, and tag its verified merged commit

### android release_artifacts

```sh
[bundle exec] fastlane android release_artifacts
```

Build and verify all signed release APK and checksum artifacts

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
