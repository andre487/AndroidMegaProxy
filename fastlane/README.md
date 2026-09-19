# MegaProxy Fastlane lanes

Fastlane is the supported entry point for tests and build artifacts. Install Ruby 3.4.10 (see `.ruby-version`), then run
`bundle install` from the repository root.

| Command | Purpose |
| --- | --- |
| `bundle exec fastlane android python_format` | Format Python with Black and isort |
| `bundle exec fastlane android python_tests` | Run Python unit tests |
| `bundle exec fastlane android python_checks` | Check Python style and run unit tests |
| `bundle exec fastlane android native_fuzz` | Fuzz native parsers for 20 seconds with two workers |
| `bundle exec fastlane android native_tests` | Run Go tests with the race detector |
| `bundle exec fastlane android android_checks` | Run Android tests and lint, build a debug APK and release APK, and prove that the release APK is unsigned |
| `bundle exec fastlane android test` | Run all native and Android checks |
| `bundle exec fastlane android debug_artifact` | Produce `app/build/outputs/apk/debug/app-debug.apk` |
| `bundle exec fastlane android release_artifacts` | Produce signed APKs, AAB, native symbols, and checksums in `dist/release` |
| `bundle exec fastlane android play_release` | Upload AAB and native symbols to Google Play (internal draft by default) |

The release lane deliberately delegates signing and artifact verification to the repository's
existing release scripts. It requires the signing environment documented in the root README.
`play_release` uploads an existing signed AAB and matching native symbols to Google Play. It
defaults to an internal draft and requires `MEGAPROXY_PLAY_JSON_KEY`; see the setup guides below.

Full setup and CI scope rules: [English](../docs/en/fastlane.md) / [Русский](../docs/ru/fastlane.md).
