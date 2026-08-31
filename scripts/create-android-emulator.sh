#!/usr/bin/env bash
set -euo pipefail

avd_name="${MEGAPROXY_AVD_NAME:-MegaProxy_API_35}"
system_image="system-images;android-35;google_apis;arm64-v8a"
device_profile="pixel_8"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    echo "ANDROID_HOME is not set" >&2
    exit 1
fi

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
emulator="$ANDROID_HOME/emulator/emulator"

for tool in "$sdkmanager" "$avdmanager"; do
    if [[ ! -x "$tool" ]]; then
        echo "Required Android SDK tool is missing: $tool" >&2
        exit 1
    fi
done

configure_avd() {
    local avd_config="$HOME/.android/avd/${avd_name}.avd/config.ini"

    if [[ ! -f "$avd_config" ]]; then
        echo "Android emulator configuration is missing: $avd_config" >&2
        exit 1
    fi

    set_avd_option() {
        local key="$1"
        local value="$2"
        local temporary_config
        temporary_config="$(mktemp "${TMPDIR:-/tmp}/megaproxy-avd-config.XXXXXX")"

        awk -F= -v key="$key" -v value="$value" '
            $1 == key {
                if (!updated) {
                    print key "=" value
                    updated = 1
                }
                next
            }
            { print }
            END {
                if (!updated) {
                    print key "=" value
                }
            }
        ' "$avd_config" >"$temporary_config"
        mv "$temporary_config" "$avd_config"
    }

    # Integrate the host keyboard and use the mouse as a multi-touch input device.
    set_avd_option "hw.keyboard" "yes"
    set_avd_option "hw.keyboard.charmap" "qwerty2"
    set_avd_option "hw.keyboard.lid" "yes"
    set_avd_option "hw.screen" "multi-touch"

    echo "Configured host keyboard and mouse input for: $avd_name"
}

if [[ ! -x "$emulator" || ! -d "$ANDROID_HOME/system-images/android-35/google_apis/arm64-v8a" ]]; then
    "$sdkmanager" \
        --sdk_root="$ANDROID_HOME" \
        "emulator" \
        "$system_image"
fi

if "$emulator" -list-avds | grep -Fxq "$avd_name"; then
    echo "Android emulator already exists: $avd_name"
    configure_avd
    exit 0
fi

echo no | "$avdmanager" create avd \
    --name "$avd_name" \
    --package "$system_image" \
    --device "$device_profile"

configure_avd

echo "Created Android emulator: $avd_name"
echo "Start it with: emulator @$avd_name -gpu auto"
