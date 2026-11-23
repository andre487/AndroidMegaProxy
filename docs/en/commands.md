# Project commands

Install JDK 21, Go and the Android SDK/NDK from [README](../../README.md#building-from-source),
and install uv. From the repository root run `uv sync --locked`. uv selects Python 3.12
from `.python-version` and installs the project dependencies into `.venv`.
Use `uv run manage.py --help` and the command group's `--help` for available options.

## Commands

| Command | Result |
| --- | --- |
| `uv run manage.py renew-config-schema` | Downloads and pins the current MegaProxyConfig schemas and examples; use `--ref FULL_SHA` for a reviewed revision. |
| `uv run manage.py update-graphics` | Generates Android/store/docs artwork from MegaProxyIdentity SVGs; use `--ref FULL_SHA` to reproduce a pinned revision. |
| `uv run manage.py python format` | Formats Python scripts with pinned Black and isort. |
| `uv run manage.py python tests` | Runs Python unit tests. |
| `uv run manage.py python checks` | Checks Python formatting/import order and runs unit tests. |
| `uv run manage.py native fuzz` | Fuzzes native parsers for 20 seconds with two workers. |
| `uv run manage.py native tests` | Runs all native Go tests and local uQUIC patch regressions with the race detector. |
| `uv run manage.py native benchmarks` | Measures native CPU/allocation costs with ten samples; [comparison and profiling](native-benchmarks.md). Available options: `--bench`, `--count`, `--benchtime`, `--cpu`, `--output`, `--profile`. |
| `uv run manage.py native integration` | Tests production dialers against real GOST/OpenSSH servers in Docker with the race detector. |
| `uv run manage.py android checks` | Builds the native AAR, resolves pinned Robolectric SDKs through Gradle, runs Android unit tests and lint, builds a debug APK, then builds and verifies an unsigned release APK. It rejects any release-signing environment variables. |
| `uv run manage.py android device-build` | Build the native AAR, debug APK, instrumentation APK and headless routing fixture. |
| `uv run manage.py android device-tests --api 26` | Run integration scenarios on a running disposable API 26 emulator. |
| `uv run manage.py android device-tests --api 35` | Run integration scenarios on a running disposable API 35 emulator. |
| `uv run manage.py test` | Runs `native tests` and `android checks`; excludes Python, Docker integration and emulator tests. |
| `uv run manage.py android debug` | Builds `app/build/outputs/apk/debug/app-debug.apk`. |
| `uv run manage.py release prepare --version 0.1.2` | Generates EN/RU notes, increments the version and creates a release PR (requires API/token setup). |
| `uv run manage.py release preview --version 1.1.3 --ref v1.1.3` | Previews concise EN/RU GitHub and F-Droid notes for an explicit Git range without publishing. |
| `uv run manage.py release finish --version 0.1.2 --pr 123 --head FULL_SHA` | Requires full CI, merges the specified PR head or accepts an existing squash merge, and tags the verified result. |
| `uv run manage.py release virus-total` | Scan signed APKs in `dist/release` (or `--artifacts PATH`); requires `VIRUSTOTAL_API_KEY`, blocks on detections/API failures. |
| `uv run manage.py release artifacts` | Builds and verifies the signed release APKs and `SHA256SUMS` in `dist/release`. |

## Choosing checks and reading results

The [testing policy](release-testing.md) defines local, CI and release checks and their limits.
`test` combines only native unit/race tests and Android JVM/lint/debug/unsigned-release checks;
Python, Docker integration and device tests are separate commands.

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
MEGAPROXY_ANDROID_ABI="$(scripts/emulator-abi.sh)" uv run manage.py android device-build
uv run manage.py android device-tests --api 35
```

The local API 35 setup and CI select `x86_64` on Intel/AMD hosts and `arm64-v8a` on ARM64 hosts. An existing default AVD with a different ABI is preserved; setup creates a separate AVD with an ABI suffix. An explicit `MEGAPROXY_AVD_NAME` must match the host ABI.

Repeat `android device-tests --api 26` on a separate API 26 emulator. Cold boot with acceleration;
unset `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY` and their lowercase variants when
launching any local emulator. No snapshot restores, software emulation or test retries.
Reports, per-case instrumentation output and Logcat go to the API-specific results directory;
failures include screenshot/UI hierarchy when available.

## Python and launcher

```sh
uv sync --locked
uv run manage.py python checks
```

Formatting, tests, the Click CLI and standalone scripts use the same uv environment.
Runtime scripts use stdlib and native `gh`. Workflow-JavaScript tests also need Node.js.
`uv run python scripts/github_actions.py` selects an open PR and reruns full CI or failed jobs;
`--dry-run` previews, `--yes` skips confirmation, `--repo OWNER/REPO` selects a repository.
It requires authenticated `gh`, a completed run for the current PR head, and rechecks that head.

## Dependencies and releases

`native integration` needs Docker and registry/package access to create disposable GOST/OpenSSH
fixtures with private HTTP/UDP origins; no personal servers or credentials are used.
It tests production dialers, payloads, authentication and trust, not Android routing/lifecycle.
Containers/networks are removed on exit; forcibly killed runs may need `megaproxy-test-*` cleanup.
`native fuzz` runs parser campaigns for 20 seconds/two workers; seeds also run in `native tests`.

`renew-config-schema [--ref FULL_SHA]` fetches MegaProxyConfig through authenticated `gh`; commit
schemas, examples, license and SHA-256 lock together. CI only reads the vendored snapshot.
Legacy import remains permissive; explicit unsupported transports fail. Update Python dependencies with `uv lock --upgrade`, review `uv.lock`, and run affected checks.

For local signed builds, prepare the pinned gomobile tools with an Android build command first.
`release artifacts` reads `MEGAPROXY_KEYSTORE_PATH`, `MEGAPROXY_KEY_ALIAS`,
`MEGAPROXY_KEY_PASSWORD_FILE` and optional `MEGAPROXY_KEY_PASSWORD`; outputs are in `dist/release`.
Defaults and signer verification are in [the release script](../../scripts/build-release-apks.sh).

Signed artifacts, API/token setup, publication and recovery: [release automation](release-automation.md).
`release finish` merges and tags; it is not a dry run. PR checks never sign releases or submit to VirusTotal.
