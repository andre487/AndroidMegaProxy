#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    # VS Code and non-interactive shells may not inherit the Android toolchain.
    source "$HOME/.zshrc.extra"
fi

: "${JAVA_HOME:=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/29.0.14206865}"
: "${MEGAPROXY_KEYSTORE_PATH:=$HOME/AndroidApkKey}"
: "${MEGAPROXY_KEY_PASSWORD_FILE:=$HOME/.my-tokens/android-key-password}"
: "${MEGAPROXY_KEY_ALIAS:=key0}"
: "${MEGAPROXY_RELEASE_DIR:=$project_dir/dist/release}"
: "${MEGAPROXY_EXPECTED_CERT_SHA256:=8a014a2a558a75b5f900ee0c33cd50f24b7432734912406699fc08866747f822}"

export JAVA_HOME ANDROID_HOME ANDROID_NDK_HOME
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

for command in go gomobile java keytool; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Required command is unavailable: $command" >&2
        exit 1
    fi
done

if [[ ! -x "$project_dir/gradlew" ]]; then
    echo "Gradle wrapper is missing or not executable: $project_dir/gradlew" >&2
    exit 1
fi
if [[ ! -f "$MEGAPROXY_KEYSTORE_PATH" ]]; then
    echo "Release keystore is missing: $MEGAPROXY_KEYSTORE_PATH" >&2
    exit 1
fi
if [[ ! -f "$MEGAPROXY_KEY_PASSWORD_FILE" ]]; then
    echo "Keystore password file is missing: $MEGAPROXY_KEY_PASSWORD_FILE" >&2
    exit 1
fi
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "Android NDK is missing: $ANDROID_NDK_HOME" >&2
    exit 1
fi

keystore_password="$(<"$MEGAPROXY_KEY_PASSWORD_FILE")"
if [[ -z "$keystore_password" ]]; then
    echo "Keystore password file is empty" >&2
    exit 1
fi
: "${MEGAPROXY_KEY_PASSWORD:=$keystore_password}"

keytool -list \
    -keystore "$MEGAPROXY_KEYSTORE_PATH" \
    -storepass:file "$MEGAPROXY_KEY_PASSWORD_FILE" \
    -alias "$MEGAPROXY_KEY_ALIAS" >/dev/null

version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"/\1/p' "$project_dir/app/build.gradle.kts" | head -n 1)"
if [[ -z "$version_name" ]]; then
    echo "Unable to determine versionName from app/build.gradle.kts" >&2
    exit 1
fi

mkdir -p "$MEGAPROXY_RELEASE_DIR" "$project_dir/app/libs"
# Avoid carrying artifacts from an older version into SHA256SUMS or a release.
find "$MEGAPROXY_RELEASE_DIR" -maxdepth 1 -type f \
    \( -name 'mega-proxy-v*.apk' -o -name mega-proxy-universal.apk -o -name SHA256SUMS \) -delete
apksigner="$ANDROID_HOME/build-tools/34.0.0/apksigner"
if [[ ! -x "$apksigner" ]]; then
    echo "Android apksigner 34.0.0 is unavailable: $apksigner" >&2
    exit 1
fi

verify_release_apk() {
    local apk="$1"
    local actual_fingerprint
    actual_fingerprint="$(
        "$apksigner" verify --verbose --print-certs "$apk" \
            | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
    )"
    if [[ "$actual_fingerprint" != "$MEGAPROXY_EXPECTED_CERT_SHA256" ]]; then
        echo "Unexpected signing certificate for $apk: $actual_fingerprint" >&2
        exit 1
    fi
}

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/megaproxy-release.XXXXXX")"
original_aar="$project_dir/app/libs/megaproxy.aar"
had_original_aar=false
if [[ -f "$original_aar" ]]; then
    cp "$original_aar" "$temporary_dir/megaproxy.aar"
    had_original_aar=true
fi
restore_workspace() {
    if [[ "$had_original_aar" == true ]]; then
        cp "$temporary_dir/megaproxy.aar" "$original_aar"
    else
        rm -f "$original_aar"
    fi
    rm -rf "$temporary_dir"
}
trap restore_workspace EXIT

export MEGAPROXY_KEYSTORE_PATH
export MEGAPROXY_KEYSTORE_PASSWORD="$keystore_password"
export MEGAPROXY_KEY_ALIAS
export MEGAPROXY_KEY_PASSWORD

echo "Running native tests"
(
    cd "$project_dir/native"
    go test ./...
)

# gomobile architecture names and their corresponding Android ABI names.
targets=(
    "arm64:arm64-v8a"
    "arm:armeabi-v7a"
    "amd64:x86_64"
    "386:x86"
)

# A clean checkout has no app/libs/megaproxy.aar. Gradle resolves local AAR
# dependencies while configuring Android unit tests, so build the first ABI
# before invoking Gradle and reuse it for the first release APK below.
first_target="${targets[0]}"
first_go_arch="${first_target%%:*}"
first_android_abi="${first_target##*:}"
echo "Building initial native AAR for $first_android_abi"
(
    cd "$project_dir/native"
    gomobile bind \
        -target="android/$first_go_arch" \
        -androidapi 26 \
        -trimpath \
        -ldflags="-s -w -buildid=" \
        -o ../app/libs/megaproxy.aar \
        ./mobile
)

echo "Running Android unit tests"
(
    cd "$project_dir"
    ./gradlew testReleaseUnitTest
)

for target in "${targets[@]}"; do
    go_arch="${target%%:*}"
    android_abi="${target##*:}"
    output_apk="$MEGAPROXY_RELEASE_DIR/mega-proxy-v${version_name}-${android_abi}.apk"

    if [[ "$android_abi" == "$first_android_abi" ]]; then
        echo "Reusing initial native AAR for $android_abi"
    else
        echo "Building optimized native AAR for $android_abi"
        (
            cd "$project_dir/native"
            gomobile bind \
                -target="android/$go_arch" \
                -androidapi 26 \
                -trimpath \
                -ldflags="-s -w -buildid=" \
                -o ../app/libs/megaproxy.aar \
                ./mobile
        )
    fi

    echo "Building and signing $android_abi APK"
    (
        cd "$project_dir"
        # Keep clean and assemble in separate Gradle invocations. When both are
        # requested in one task graph, Gradle does not guarantee that clean has
        # finished before every generated-resource task starts.
        ./gradlew clean
        ./gradlew assembleRelease
    )
    built_apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
    if [[ ! -f "$built_apk" ]]; then
        echo "Gradle did not produce the expected APK: $built_apk" >&2
        exit 1
    fi
    cp "$built_apk" "$output_apk"

    verify_release_apk "$output_apk"
done

# F-Droid verifies this universal APK against a clean source build before
# publishing it with the upstream signature. Keep its gomobile and Gradle
# inputs identical to the F-Droid recipe in .fdroid.yml.
echo "Building optimized native AAR for the universal APK"
(
    cd "$project_dir/native"
    gomobile bind \
        -target=android \
        -androidapi 26 \
        -trimpath \
        -ldflags="-s -w -buildid=" \
        -o ../app/libs/megaproxy.aar \
        ./mobile
)

echo "Building and signing universal APK"
(
    cd "$project_dir"
    ./gradlew clean
    ./gradlew assembleRelease
)
universal_apk="$MEGAPROXY_RELEASE_DIR/mega-proxy-universal.apk"
built_apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$built_apk" ]]; then
    echo "Gradle did not produce the expected APK: $built_apk" >&2
    exit 1
fi
cp "$built_apk" "$universal_apk"
verify_release_apk "$universal_apk"

(
    cd "$MEGAPROXY_RELEASE_DIR"
    shasum -a 256 ./*.apk > SHA256SUMS
)

echo
echo "Signed release APKs:"
ls -lh "$MEGAPROXY_RELEASE_DIR"/*.apk
echo "Checksums: $MEGAPROXY_RELEASE_DIR/SHA256SUMS"
