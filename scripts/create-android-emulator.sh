#!/usr/bin/env bash
set -euo pipefail

avd_name="${MEGAPROXY_AVD_NAME:-MegaProxy_API_35}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
abi="$("$script_dir/emulator-abi.sh")"
system_image="system-images;android-35;google_apis;$abi"
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

avd_config_path() {
    local avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
    local avd_dir=""
    if [[ -f "$avd_home/$avd_name.ini" ]]; then
        avd_dir=$(sed -n 's/^path=//p' "$avd_home/$avd_name.ini")
    fi
    echo "${avd_dir:-$avd_home/$avd_name.avd}/config.ini"
}

avd_abi() {
    awk -F= '$1 ~ /^abi[.]type[[:space:]]*$/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$1"
}

configure_avd() {
    local avd_config
    avd_config=$(avd_config_path)

    if [[ ! -f "$avd_config" ]]; then
        echo "Android emulator configuration is missing: $avd_config" >&2
        exit 1
    fi

    local configured_abi
    configured_abi=$(avd_abi "$avd_config")
    if [[ "$configured_abi" != "$abi" ]]; then
        echo "AVD $avd_name uses ${configured_abi:-unknown}; host requires $abi. Set MEGAPROXY_AVD_NAME to a new name to keep the existing device." >&2
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

    echo "Configured host keyboard and mouse input for: $avd_name" >&2
}

if [[ ! -x "$emulator" || ! -f "$ANDROID_HOME/system-images/android-35/google_apis/$abi/system.img" ]]; then
    "$sdkmanager" \
        --sdk_root="$ANDROID_HOME" \
        "emulator" \
        "$system_image" >&2
fi

# Keep the data of an existing default AVD when switching host architectures.
legacy_config=$(avd_config_path)
if [[ -z "${MEGAPROXY_AVD_NAME:-}" && -f "$legacy_config" && "$(avd_abi "$legacy_config")" != "$abi" ]]; then
    avd_name="${avd_name}_${abi}"
fi

if "$emulator" -list-avds | grep -Fxq "$avd_name"; then
    echo "Android emulator already exists: $avd_name" >&2
else
    echo no | "$avdmanager" create avd \
        --name "$avd_name" \
        --package "$system_image" \
        --device "$device_profile" >&2
fi

configure_avd
echo "$avd_name"
