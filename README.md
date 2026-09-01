# MegaProxy

[![CI](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml/badge.svg)](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)

<p align="center">
  <img src="assets/branding/megaproxy-launcher-master.png" width="160" alt="MegaProxy app icon">
</p>

MegaProxy is an open-source Android VPN client for reliable, secure connections through proxy
servers you control or trust. It supports HTTPS and SSH transports, per-app routing, encrypted DNS,
connection diagnostics, and automatic failover in one privacy-focused application.

MegaProxy contains no advertising, analytics SDKs, tracking, or remote telemetry. Connection
statistics and diagnostic logs stay on the device unless you explicitly choose to share them.

> MegaProxy is under active development. Review the current limitations before relying on it for
> critical connectivity.

## Why MegaProxy

- **Private by design.** No account, ads, analytics, tracking identifiers, or background telemetry.
- **Your infrastructure.** Connect to HTTPS, SSH, or SSH-with-jump servers that you configure.
- **End-to-end application encryption.** HTTPS proxying uses CONNECT without intercepting or
  decrypting application traffic.
- **Flexible routing.** Route the whole device or only selected applications through the VPN.
- **Resilient connections.** Profile failover, encrypted DNS fallback, SSH keepalives, and
  connection health reporting help recover from network and server failures.
- **Fully open source.** The Android application and Go network core are available under the MIT
  License.

## Features

### Connection profiles

- Multiple named, colored, reorderable profiles.
- HTTPS proxies over TLS with Basic authentication.
- HTTP/2 CONNECT multiplexing when supported by the proxy, with automatic HTTP/1.1 fallback.
- SSH `direct-tcpip` transport and SSH through a jump host.
- SSH password and unencrypted private-key authentication.
- SSH host-key verification with trust-on-first-use confirmation.
- Profile cloning, import, export, and configurable failover.
- Import from MegaProxy JSON, ProxyList, FoxyProxy JSON, and supported SuperProxy text files.

### VPN and routing

- Global VPN and per-app split tunneling.
- Local-network bypass enabled by default.
- Per-profile IPv6 support; IPv4-only operation is the default.
- Android Always-on VPN integration and a persistent foreground-service notification.
- Automatic reconnect when the active profile or pending connection settings change.
- Approximate upload speed, download speed, proxy latency, and recent connection-error rate.

### DNS and transport

- DNS-over-HTTPS through the configured transport.
- Cloudflare, Google, Quad9, Yandex Basic, Yandex Safe, Yandex Family, and custom DoH endpoints.
- DNS-provider fallback where it does not weaken an explicitly selected filtering policy.
- HTTPS ClientHello profiles powered by uTLS, plus manual JA3 configuration.
- Configurable SSH client profiles, keepalives, channel limits, and session rotation.
- Arbitrary UDP is intentionally not forwarded; QUIC/HTTP/3 clients normally fall back to TCP.

### Diagnostics

- A staged connection test for proxy setup, `example.com`, and the observed exit IP.
- Local, size-limited, rotating diagnostic and crash logs designed to omit credentials and traffic
  content.
- On-device connection visibility checks and actionable connection warnings.
- Optional feedback or crash reports opened in the user's email client; nothing is submitted
  automatically.
- English and Russian interfaces with an in-app language selector.

## Privacy and security

MegaProxy does not operate a proxy service and does not send configuration or usage data to the
project author. Network traffic is sent only where required by the selected profile, destination,
and DNS configuration. The explicit connection test additionally contacts `example.com` and
`ifconfig.me`.

- HTTPS proxy certificates are checked against the Android trust store, including hostname and
  validity. Normal CA certificate renewal does not require certificate pinning.
- Application TLS remains between the application and its destination. MegaProxy does not install
  a CA certificate and does not perform TLS interception.
- Proxy passwords and imported private keys are encrypted with AES-GCM using a key held by Android
  Keystore.
- Android cloud backup and device-to-device transfer are disabled for application data.
- Every upstream socket is protected from recursive routing through the VPN.
- SSH host keys are verified and unknown keys require explicit user confirmation.

Two compatibility options deliberately reduce these protections: accepting an invalid HTTPS
proxy certificate and accepting any SSH host key. MegaProxy displays a warning before enabling
them. Use either option only when you understand and control the associated risk.

The proxy or SSH server can observe connection metadata and the destinations it is asked to reach,
even though it cannot read end-to-end encrypted application content. The operator of that server
must therefore be trusted. No application can override an explicit Android force-stop, and device
vendors may impose additional background-execution restrictions.

## Installation

Download an APK for your device architecture from
[GitHub Releases](https://github.com/andre487/AndroidMegaProxy/releases). Most modern Android
phones use `arm64-v8a`. Android 8.0 (API 26) or newer is required.

Release APKs are signed by the project's release key. SHA-256 checksums are published alongside
the APK files. Android will preserve application data across upgrades only when the package name
and signing key remain unchanged.

After installation:

1. Create or import a connection profile.
2. Choose global routing or select applications for split tunneling.
3. Review DNS and fingerprint settings if the defaults are not appropriate for your server.
4. Tap **Test** to validate the connection, then tap **Connect**.
5. Optionally enable Always-on VPN in Android settings.

For production-ready HTTPS, SSH, and SSH-with-jump server configurations, see the
[English server guide](docs/en/index.md) or [Russian server guide](docs/ru/index.md).

## Current limitations

- Only TCP application traffic is forwarded. General SOCKS5 UDP and QUIC forwarding are not
  implemented.
- SSH private keys protected by a passphrase are not supported yet.
- Browser and SSH fingerprint presets are version-specific approximations. A preset name is not a
  permanent guarantee of an exact client fingerprint.
- Edge Android, Samsung Internet, and Yandex Browser TLS presets remain unavailable until verified
  Android ClientHello fixtures are added.
- Always-on behavior ultimately depends on Android and the device vendor. An explicit force-stop
  cannot be recovered from programmatically.

## Building from source

The command-line build does not require Android Studio. It requires:

- JDK 17
- Go 1.26 or newer
- `gomobile`
- Android SDK Platform 35
- Android Build Tools 34.0.0
- Android NDK 29.0.14206865

Example environment on macOS:

```shell
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
```

Build the Go core and Android AAR:

```shell
cd native
go test ./...
gomobile bind -target=android -androidapi 26 -o ../app/libs/megaproxy.aar ./mobile
cd ..
```

Build and test the Android application:

```shell
./gradlew testDebugUnitTest assembleDebug
```

Install the debug APK on a connected device:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n net.megaproxy487/.MainActivity
```

The project pins Gradle 8.9 through the checked-in wrapper. Use `./gradlew` rather than a globally
installed Gradle version. See [native/README.md](native/README.md) for Go data-plane details.

## Development workflow

English is the project language for source code, comments, documentation, commit messages, UI
copy, logs, and tooling.

### Emulator

Create the API 35 Google APIs ARM64 emulator:

```shell
./scripts/create-android-emulator.sh
```

The script installs missing components, configures host keyboard and mouse input, and can be run
more than once. It creates `MegaProxy_API_35` by default; set `MEGAPROXY_AVD_NAME` to override the
name.

Start the application without a debugger:

```shell
./scripts/run-without-debugging.sh
```

The repository also includes VS Code tasks and launch configurations for creating and starting the
emulator, building, installing, viewing app-specific Logcat output, running tests, and attaching a
JDWP debugger. Select **Run MegaProxy on Emulator** for `Ctrl+F5` or **Attach MegaProxy (JDWP)** for
`F5`.

Run all native and Android unit tests from VS Code with **Tasks: Run Test Task**, or from a shell:

```shell
(cd native && go test ./...)
./gradlew testDebugUnitTest
```

JDWP covers Kotlin and Java code only. Debug the Go core with its tests and privacy-safe diagnostic
logging.

### Signed release builds

Build optimized and signed APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`:

```shell
./scripts/build-release-apks.sh
```

The script reads its default signing key from `$HOME/AndroidApkKey` and its password from
`$HOME/.my-tokens/android-key-password`. Override these with `MEGAPROXY_KEYSTORE_PATH`,
`MEGAPROXY_KEY_ALIAS`, `MEGAPROXY_KEY_PASSWORD_FILE`, and `MEGAPROXY_KEY_PASSWORD`. Outputs and
`SHA256SUMS` are written to `dist/release`.

Pushing a version tag runs the GitHub release workflow, builds and verifies every APK, and attaches
the artifacts to a GitHub Release. The tag must match `versionName` exactly:

```shell
git tag v0.0.2
git push origin v0.0.2
```

## Contributing

Bug reports and focused pull requests are welcome. Please avoid including proxy credentials,
private keys, destination history, or other personal data in issues and logs. Run both the Go and
Android unit-test suites before opening a pull request.

## License

MegaProxy is released under the [MIT License](LICENSE).
