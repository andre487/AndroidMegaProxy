# MegaProxy project memory

This file records stable project decisions and collaboration conventions for future coding sessions.
Keep it concise and update it when a decision changes; do not add transient CI run IDs, temporary
branch names, credentials, signing material, or other secrets.

## Build and tooling

- JDK 21 is the project toolchain. CI and F-Droid builds must use JDK 21. Local configuration must
  discover a compatible JDK without hard-coding a Homebrew, macOS architecture, or user-specific
  installation path.
- Fastlane is the primary supported entry point for builds, checks, tests, and release artifacts.
  Run it through Bundler (`bundle exec fastlane ...`) so `Gemfile.lock` controls dependency versions.
  Keep the English and Russian command reference in `docs/en/fastlane.md` and
  `docs/ru/fastlane.md` synchronized with the lanes in `fastlane/Fastfile`.
- Direct scripts and Gradle tasks may remain implementation details behind Fastlane lanes, but
  documentation and CI should normally expose the Fastlane commands.

## CI and artifacts

- Pull requests must run native tests, Android unit/lint/build checks, and instrumented Compose UI
  tests.
- PR builds may publish debug and unsigned APK artifacts. They must never have access to release
  signing material and must never produce or publish a signed release APK.
- Surface downloadable APK artifacts in the GitHub Actions job summary in addition to uploading
  them through `actions/upload-artifact`.
- UI tests use the lightweight API 30 `aosp_atd` x86_64 image. KVM acceleration is required: fail
  quickly with a clear diagnostic when `/dev/kvm` is unavailable instead of falling back to the
  unstable software emulator. An infrastructure retry should use a fresh job/runner rather than
  repeatedly invoking instrumentation in the same crashed emulator.
- Treat `Starting 0 tests`, missing XML reports, or fewer than the expected tests as a CI failure;
  a green Gradle process alone is not proof that instrumentation tests ran.

## Releases and distribution

- Release builds and signing are separate from PR CI. Release artifacts are created only through
  the dedicated release workflow/Fastlane lane.
- After creating and verifying a release, update the corresponding F-Droid submission/build recipe
  when required. The repository's F-Droid-related files are for reproducible verification, not an
  excuse to maintain a duplicate unused build path.
- Store F-Droid listing metadata and current store artwork under `fastlane/metadata/android`.
  Obsolete artwork does not need an archive copy in the working tree because Git preserves history.

## Product and UI conventions

- All user-visible UI text must use Android string resources and be supplied in both the default
  English resources and Russian resources. Do not localize standardized data-unit symbols.
- Traffic totals and rates share the selected unit system. IEC is the default and uses powers of
  1024 with Latin symbols (`KiB`, `MiB`, `GiB`, `TiB`, `PiB`); users can select SI powers of 1000
  with Latin symbols (`KB`, `MB`, `GB`, `TB`, `PB`).
- Traffic-limit inputs remain in `MiB` and must be labelled exactly with the Latin `MiB` symbol.
- In Russian UI, translate “samples” as “сэмплы”, not “попытки”. Relative latency timestamps belong
  on a separate line in smaller text.
- Connection diagnostics include exit IP and country, with fallback providers so one unavailable
  external service does not make the whole check fail. UI tests should assert that country is
  actually presented, not merely fetched internally.

## Architecture landmarks

- `ProxyVpnService` extends Android's standard `android.net.VpnService`. It owns VPN lifecycle,
  creates the TUN interface, coordinates profiles/reconnects/status, and hands the TUN file
  descriptor to the native networking layer. Native code performs the actual proxy forwarding.
- Navigation uses a single activity/back stack. Navigation, localization, settings persistence,
  and cross-screen behavior should be covered by Compose instrumentation tests.

## Collaboration workflow

- Put each new change on a branch based on the current `main` and normally deliver it as one focused
  GitHub pull request. After a PR is merged, start subsequent work from the updated `main` instead of
  continuing on the merged branch.
- Before changing CI after a failure, inspect the full job log and distinguish application test
  failures from runner/emulator infrastructure failures.
