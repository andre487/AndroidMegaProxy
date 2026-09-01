# MegaProxy

[![CI](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml/badge.svg)](https://github.com/andre487/AndroidMegaProxy/actions/workflows/ci.yml)

An Android client that selectively tunnels TCP traffic from chosen applications through an
HTTPS proxy (`TLS -> HTTP CONNECT`) with Basic Auth and a configurable TLS ClientHello.

## Design guarantees

- The proxy does not perform MITM. Application TLS remains end-to-end between the application
  and the destination website.
- The proxy certificate is verified against the Android system trust store, hostname and
  validity period. Certificate pinning is not used, so regular Let's Encrypt rotation works.
- Only selected packages enter the TUN interface (`VpnService.Builder.addAllowedApplication`).
- UDP is blocked. In particular, QUIC/HTTP3 falls back to TCP.
- IPv4-only destination mode is enabled by default for HTTPS proxies without IPv6 CONNECT
  support. IPv6 destinations can be enabled from the split-tunneling screen.
- DNS from selected applications is intercepted inside the TUN, converted to DoH and sent
  through the proxy. Cloudflare, Google, Quad9 and a custom HTTPS endpoint are supported.
- Always-on VPN support is declared in the manifest. The foreground service returns
  `START_STICKY`.
- The UI links to the system Always-on VPN settings and requests exemption from battery
  optimization. No Android application can survive an explicit user force-stop.
- The Basic Auth password is encrypted with an AES-GCM key stored in Android Keystore.

## Implementation status

The Android project includes configuration UI, application selection, manual JA3 validation,
secure credential storage and the per-app VPN lifecycle. The native data plane is implemented
in Go with uTLS and the gVisor network stack.

The main screen provides a connection test that uses the configured HTTPS proxy and TLS profile,
verifies an end-to-end HTTPS request to `example.com`, and obtains the proxy exit IP from
`ifconfig.me`. The test runs in a dedicated screen with an isolated per-run diagnostic log;
credentials and traffic content are never logged. The compact main screen contains connection
status and Connect/Disconnect, Test and Settings actions. Proxy, JA3, DoH, split tunneling,
Always-on VPN and battery controls are available from Settings.

The foreground VPN service persists whether a normal connection is desired. While desired, it
monitors tunnel state, retries failed startup and republishes its ongoing notification every ten
seconds. Android Always-on remains the authority that restarts the service after reboot or process
termination; an explicit system force-stop cannot be overridden by an application.

Every physical upstream socket must be passed to `VpnService.protect()` before `connect()` to
prevent recursive routing into the TUN interface.

## JA3 profiles

A browser name is not a permanent fingerprint. Every profile must be versioned and backed by
a captured ClientHello fixture. uTLS provides Chrome, Firefox, Edge and randomized profiles.
Manual mode accepts the canonical JA3 string:

`TLSVersion,CipherSuites,Extensions,SupportedGroups,ECPointFormats`

Edge Android, Samsung Internet and Yandex Browser remain disabled until verified ClientHello
fixtures are available for specific Android browser versions. The application intentionally
does not relabel a generic desktop or Chromium profile as one of these browsers.

## Build

Use JDK 17 to build the Android application. Building the Go AAR additionally requires Go and
`gomobile`; see `native/README.md` for details.

## Development

English is the project language. Use English for source code, comments, documentation, commit
messages, UI copy, logs and developer tooling.

### Prerequisites

The command-line build requires JDK 17, Go 1.26 or newer, gomobile, Android SDK Platform 35,
Build Tools 34.0.0 and NDK 29.0.14206865. The expected environment is:

```shell
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
```

The project pins Gradle 8.9 through the checked-in wrapper. Use `./gradlew`, not a globally
installed Gradle version.

### Create and start the emulator

Create the API 35 Google APIs ARM64 emulator once:

```shell
./scripts/create-android-emulator.sh
```

The script installs the emulator and system image when needed. It is idempotent and uses
`MegaProxy_API_35` by default. It also enables the host physical keyboard and configures
the mouse as multi-touch input, including for an existing AVD. Override the name with
`MEGAPROXY_AVD_NAME` if necessary. Restart a running emulator after changing its AVD
configuration.

The startup script always uses a cold boot because stale Quick Boot snapshots can leave the guest
visible but permanently offline in ADB. It attempts to reconnect an existing offline emulator and,
if that fails, terminates only that AVD process before restarting it. ADB and Android boot waits
have finite timeouts.

Start it from the terminal:

```shell
emulator @MegaProxy_API_35 -gpu auto
```

### Build from the terminal

Build and test the Go core, then produce the AAR:

```shell
cd native
go test ./...
gomobile bind -target=android -androidapi 26 -o ../app/libs/megaproxy.aar ./mobile
cd ..
```

Build, test and install the Android application:

```shell
./gradlew testDebugUnitTest assembleDebug
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n net.megaproxy487/.MainActivity
```

### Signed release APKs

Build R8-optimized, resource-shrunk and signed APKs for every supported Android ABI:

```shell
./scripts/build-release-apks.sh
```

By default the script uses `$HOME/AndroidApkKey`, alias `key0`, and reads the shared keystore/key
password from `$HOME/.my-tokens/android-key-password`. These locations can be overridden with
`MEGAPROXY_KEYSTORE_PATH`, `MEGAPROXY_KEY_ALIAS`, `MEGAPROXY_KEY_PASSWORD_FILE`, and
`MEGAPROXY_KEY_PASSWORD`. Outputs and their SHA-256 checksums are written to `dist/release`.
Separate APKs are produced for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`; each APK contains
only its matching native library.

### VS Code workflow

Open the repository in VS Code and install the workspace recommendations from
`.vscode/extensions.json`. Run tasks through `Tasks: Run Task`:

- `Android: Create emulator` creates the required AVD.
- `Android: Start emulator` starts `MegaProxy_API_35`.
- `Android: Run on emulator` creates or starts the emulator, builds, installs and launches the app.
- `Android: Build native AAR` builds the Go bridge.
- `Android: Build debug APK` builds the AAR and debug APK.
- `Android: Install debug APK` builds and installs the application.
- `Android: Launch app` builds, installs and launches the application.
- `Android: Logcat (app)` follows logs from the MegaProxy process only.
- `Android: Prepare JDWP` restarts the app in debugger-wait mode and forwards port 8700.
- `Android: Clear JDWP` removes the debugger setting and port forwarding.
- `Test: Go core` runs all tests in the native Go module.
- `Test: Android unit tests` runs the debug JVM unit-test suite.
- `Test: All` runs both suites in parallel and is the default VS Code test task.

Run the default test task with `Tasks: Run Test Task`. Test reports from the Android suite are
written to `app/build/reports/tests/testDebugUnitTest/index.html`.

For breakpoints, select `Attach MegaProxy (JDWP)` in the Run and Debug view and press F5.
The pre-launch task creates or starts the emulator, waits for Android to boot, builds and
installs the app, restarts it in debugger-wait mode and configures JDWP forwarding automatically.
The task remains active for the lifetime of the debug session so the ADB server and forwarding
socket are not torn down before the debugger attaches.

For a regular run, select `Run MegaProxy on Emulator` in the Run and Debug view and use
`Run Without Debugging` (`Ctrl+F5`). The configuration creates the AVD when missing, starts it,
waits for Android to boot, builds the native AAR and APK, installs the APK and launches the app.
It runs `scripts/run-without-debugging.sh`, which also clears stale JDWP application state and
port forwarding left by an interrupted debug session. The same script can be run directly:

```shell
./scripts/run-without-debugging.sh
```

Both regular and debug launch scripts terminate an existing MegaProxy application or JDWP
session before starting a new one. They keep the emulator itself running.

JDWP debugging covers the Kotlin/Java layer. It cannot step into Go code inside
`libgojni.so`; debug that layer with Go tests and logging. Direct Go debugging on Android
would require a separate Delve-based workflow.
