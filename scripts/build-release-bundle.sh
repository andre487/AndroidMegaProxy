#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    source "$HOME/.zshrc.extra"
fi

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/29.0.14206865}"
: "${MEGAPROXY_KEYSTORE_PATH:=$HOME/AndroidApkKey}"
: "${MEGAPROXY_KEY_PASSWORD_FILE:=$HOME/.my-tokens/android-key-password}"
: "${MEGAPROXY_KEY_ALIAS:=key0}"
: "${MEGAPROXY_RELEASE_DIR:=$project_dir/dist/release}"
: "${MEGAPROXY_EXPECTED_CERT_SHA256:=8a014a2a558a75b5f900ee0c33cd50f24b7432734912406699fc08866747f822}"

export JAVA_HOME ANDROID_HOME ANDROID_NDK_HOME
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

for command in go gomobile java jarsigner keytool unzip; do
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

mkdir -p "$MEGAPROXY_RELEASE_DIR" "$project_dir/app/libs"
find "$MEGAPROXY_RELEASE_DIR" -maxdepth 1 -type f -name 'mega-proxy.aab' -delete

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/megaproxy-bundle.XXXXXX")"
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

echo "Building universal native AAR for the App Bundle"
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

echo "Building signed App Bundle"
(
    cd "$project_dir"
    ./gradlew clean
    ./gradlew bundleRelease -PmegaproxyVersionVariant=universal
)

built_bundle="$project_dir/app/build/outputs/bundle/release/app-release.aab"
output_bundle="$MEGAPROXY_RELEASE_DIR/mega-proxy.aab"
if [[ ! -f "$built_bundle" ]]; then
    echo "Gradle did not produce the expected App Bundle: $built_bundle" >&2
    exit 1
fi
cp "$built_bundle" "$output_bundle"

jarsigner -verify "$output_bundle" >/dev/null
actual_fingerprint="$(
    keytool -printcert -jarfile "$output_bundle" \
        | awk '/SHA256:/{print $2; exit}' \
        | tr -d ':' \
        | tr '[:upper:]' '[:lower:]'
)"
if [[ "$actual_fingerprint" != "$MEGAPROXY_EXPECTED_CERT_SHA256" ]]; then
    echo "Unexpected signing certificate for $output_bundle: $actual_fingerprint" >&2
    exit 1
fi

expected_abis=(arm64-v8a armeabi-v7a x86 x86_64)
bundle_entries="$temporary_dir/bundle-entries.txt"
unzip -Z1 "$output_bundle" > "$bundle_entries"
for abi in "${expected_abis[@]}"; do
    for library in libandroidx.graphics.path.so libgojni.so; do
        entry="base/lib/$abi/$library"
        if ! grep -Fxq "$entry" "$bundle_entries"; then
            echo "App Bundle is missing $entry" >&2
            exit 1
        fi
    done
done

(
    cd "$MEGAPROXY_RELEASE_DIR"
    checksum_files=()
    while IFS= read -r file; do
        checksum_files+=("$file")
    done < <(find . -maxdepth 1 -type f \( -name 'mega-proxy-*.apk' -o -name 'mega-proxy.aab' \) -print | sort)
    shasum -a 256 "${checksum_files[@]}" > SHA256SUMS
)

echo
echo "Signed release App Bundle:"
ls -lh "$output_bundle"
echo "Checksums: $MEGAPROXY_RELEASE_DIR/SHA256SUMS"
