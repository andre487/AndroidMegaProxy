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

- Python runtime scripts use the standard library and native `gh` for GitHub access. Format with
  pinned Black/isort through `python_format`; `python_tests` and `python_checks` use `PYTHON`.

## CI and artifacts

- Pull requests must run native tests and Android JVM unit/lint/build checks. Do not require an
  Android emulator in GitHub Actions: hosted-runner KVM availability proved too unreliable for a
  trustworthy required check.
- Compare each suite against its last successful ancestor check in the same PR and base.
  Failed/skipped/cancelled jobs do not advance coverage. Fall back to the full PR diff when
  history is unavailable; unknown paths and shared build/CI inputs enable all suites. Require `Change scope` and `Python tests and style`
  alongside native/Android checks when this workflow is adopted.
- PR builds may publish debug and unsigned APK artifacts. They must never have access to release
  signing material and must never produce or publish a signed release APK.
- Surface downloadable APK artifacts in the GitHub Actions job summary in addition to uploading
  them through `actions/upload-artifact`.
- Prefer extracting UI-facing decisions into small production contracts and testing those with
  deterministic JVM unit tests. Resource parity, navigation destination wiring, preference
  serialization/defaults, formatting, and state transitions should not require a device.
- Keep device-only tests out of required GitHub CI unless the project later adopts a dependable
  device farm or controlled self-hosted runner. Do not reintroduce a software-emulated Android
  fallback.

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
  English resources and Russian resources. The app language selects UI words; number, date and
  other data formatting follows the system locale. Do not localize standardized data-unit symbols.
- Traffic totals and rates share the selected unit system. IEC is the default and uses powers of
  1024 with Latin symbols (`KiB`, `MiB`, `GiB`, `TiB`, `PiB`); users can select SI powers of 1000
  with Latin symbols (`KB`, `MB`, `GB`, `TB`, `PB`).
- Traffic-limit inputs remain in `MiB` and must be labelled exactly with the Latin `MiB` symbol.
- In Russian UI, translate “samples” as “сэмплы”, not “попытки”. Relative latency timestamps belong
  on a separate line in smaller text.
- Connection diagnostics include exit IP and country, with fallback providers so one unavailable
  external service does not make the whole check fail. Keep presentation inputs and formatting
  covered by JVM tests.

- Keep screens usable on narrow windows and with enlarged system fonts. Let actions and status
  rows wrap or stack; constrain app-bar titles and field labels, and make long dialog content
  scrollable. Verify visual changes locally without adding emulator requirements to GitHub CI.

- Configuration writes must outlive individual screens and expose pending/failure state. Keep
  transfer operations across configuration changes; never put credentials or export payloads into
  Android saved-state bundles, and reject a lost export before opening the output stream.

## Architecture landmarks

- `ProxyVpnService` extends Android's standard `android.net.VpnService`. It owns VPN lifecycle,
  creates the TUN interface, coordinates profiles/reconnects/status, and hands the TUN file
  descriptor to the native networking layer. Native code performs the actual proxy forwarding.
- Navigation uses a single activity/back stack. Keep route and settings-destination definitions in
  shared production contracts whose completeness and uniqueness can be checked by JVM tests.

## Collaboration workflow

- Put each new change on a branch based on the current `main` and normally deliver it as one focused
  GitHub pull request. After a PR is merged, start subsequent work from the updated `main` instead of
  continuing on the merged branch.
- Keep required CI deterministic. If a check depends on unreliable hosted-runner capabilities,
  replace it with JVM coverage where practical or move it to purpose-built infrastructure rather
  than normalizing repeated reruns.
