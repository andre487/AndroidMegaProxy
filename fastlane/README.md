# MegaProxy Fastlane lanes

Fastlane is the supported entry point for tests and build artifacts. Install Ruby 3.4, then run
`bundle install` from the repository root.

| Command | Purpose |
| --- | --- |
| `bundle exec fastlane android python_format` | Format Python with Black and isort |
| `bundle exec fastlane android python_checks` | Check Python style and runner contracts |
| `bundle exec fastlane android native_tests` | Run Go tests with the race detector |
| `bundle exec fastlane android android_checks` | Run Android tests and lint, build a debug APK and release APK, and prove that the release APK is unsigned |
| `bundle exec fastlane android test` | Run all native and Android checks |
| `bundle exec fastlane android debug_artifact` | Produce `app/build/outputs/apk/debug/app-debug.apk` |
| `bundle exec fastlane android release_artifacts` | Produce signed APKs, AAB, native symbols, and checksums in `dist/release` |

The release lane deliberately delegates signing and artifact verification to the repository's
existing release scripts. It requires the signing environment documented in the root README.
Publishing to an app store is not performed by any lane.

Real-device UI tests use `ui_test_artifacts` followed by `selectel_ui_tests`.
Pass `profile:additional` to both lanes for the four optional configurations, excluding the required Android 15 device. See the complete
[English command reference](../docs/en/fastlane.md), [Russian command reference](../docs/ru/fastlane.md),
and [Selectel setup](../docs/en/selectel-ui-tests.md) for probe, lease and recovery commands.
