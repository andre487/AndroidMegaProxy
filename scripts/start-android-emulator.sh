#!/usr/bin/env bash
set -euo pipefail

avd_name="${MEGAPROXY_AVD_NAME:-MegaProxy_API_35}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$script_dir/create-android-emulator.sh"

if adb devices | awk 'NR > 1 && $1 ~ /^emulator-/ { found = 1 } END { exit !found }'; then
    echo "An Android emulator is already running"
else
    emulator_log="${TMPDIR:-/tmp}/megaproxy-emulator.log"
    echo "Starting Android emulator: $avd_name"
    nohup emulator "@$avd_name" -gpu auto </dev/null >"$emulator_log" 2>&1 &
fi

adb -e wait-for-device

echo "Waiting for Android to finish booting"
for _ in {1..180}; do
    boot_completed="$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$boot_completed" == "1" ]]; then
        echo "Android emulator is ready"
        exit 0
    fi
    sleep 1
done

echo "Android emulator did not finish booting within 180 seconds" >&2
exit 1
