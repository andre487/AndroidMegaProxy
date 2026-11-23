#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    # VS Code launched from Finder may not inherit the interactive shell environment.
    source "$HOME/.zshrc.extra"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ndk_version=29.0.14206865
    export ANDROID_NDK_HOME="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}/ndk/$ndk_version"
    if [[ ! -f "$ANDROID_NDK_HOME/meta/platforms.json" ]]; then
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "ndk;$ndk_version"
    fi
fi
if [[ ! -f "$ANDROID_NDK_HOME/meta/platforms.json" ]]; then
    echo "Android NDK is incomplete: $ANDROID_NDK_HOME/meta/platforms.json is missing" >&2
    exit 1
fi

"$project_dir/scripts/start-android-emulator.sh"

(
    cd "$project_dir"
    # gomobile builds generated bindings outside the checkout. The APK records the project commit.
    GOFLAGS="${GOFLAGS:+$GOFLAGS }-buildvcs=false" uv run manage.py android debug
)

adb -e install -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
adb -e shell am start -n net.megaproxy487/.MainActivity
