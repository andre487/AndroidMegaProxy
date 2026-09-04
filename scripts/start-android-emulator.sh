#!/usr/bin/env bash
set -euo pipefail

avd_name="${MEGAPROXY_AVD_NAME:-MegaProxy_API_35}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$script_dir/create-android-emulator.sh"

avd_processes() {
    ps ax -o pid=,command= | awk -v avd="@$avd_name" '
        index($0, avd) && ($0 ~ /\/emulator / || $0 ~ /qemu-system/) { print $1; next }
        index($0, "-avd " substr(avd, 2)) && ($0 ~ /\/emulator / || $0 ~ /qemu-system/) { print $1 }
    '
}

headless_avd_processes() {
    ps ax -o pid=,command= | awk -v avd="$avd_name" '
        (index($0, "@" avd) || index($0, "-avd " avd)) &&
        ($0 ~ /\/emulator / || $0 ~ /qemu-system/) &&
        ($0 ~ /-no-window/ || $0 ~ /qemu-system-[^ ]*-headless/) { print $1 }
    '
}

emulator_serial="$(adb devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1; exit }')"
emulator_state=""
restart_for_window=false

if [[ -n "$emulator_serial" ]]; then
    emulator_state="$(adb -s "$emulator_serial" get-state 2>/dev/null || true)"
fi

if [[ "$emulator_state" == "device" && -n "$(headless_avd_processes)" ]]; then
    echo "A headless Android emulator is running; restarting it with a window"
    restart_for_window=true
fi

if [[ "$restart_for_window" != "true" && -n "$emulator_serial" && "$emulator_state" != "device" ]]; then
    echo "Android emulator is $emulator_state; attempting ADB reconnect"
    adb reconnect offline >/dev/null 2>&1 || true
    for _ in {1..20}; do
        emulator_state="$(adb -s "$emulator_serial" get-state 2>/dev/null || true)"
        [[ "$emulator_state" == "device" ]] && break
        sleep 0.25
    done
fi

if { [[ "$restart_for_window" == "true" ]] ||
     [[ -n "$emulator_serial" && "$emulator_state" != "device" ]] ||
     { [[ -z "$emulator_serial" ]] && [[ -n "$(avd_processes)" ]]; }; }; then
    if [[ -n "$emulator_serial" ]]; then
        echo "Stopping non-windowed or unresponsive emulator: $emulator_serial"
        adb -s "$emulator_serial" emu kill >/dev/null 2>&1 || true
    else
        echo "Stopping orphaned emulator process for: $avd_name"
    fi
    while read -r emulator_pid; do
        if [[ "$emulator_pid" =~ ^[0-9]+$ ]]; then
            kill -TERM "$emulator_pid" >/dev/null 2>&1 || true
        fi
    done < <(avd_processes)
    for _ in {1..120}; do
        [[ -z "$(avd_processes)" ]] && break
        sleep 0.25
    done
    while read -r emulator_pid; do
        if [[ "$emulator_pid" =~ ^[0-9]+$ ]]; then
            echo "Force-stopping stale emulator process: $emulator_pid"
            kill -KILL "$emulator_pid" >/dev/null 2>&1 || true
        fi
    done < <(avd_processes)
    sleep 1
    emulator_serial=""
fi

if [[ -n "$emulator_serial" && "$emulator_state" == "device" ]]; then
    echo "An Android emulator is already running"
else
    emulator_log="${TMPDIR:-/tmp}/megaproxy-emulator.log"
    echo "Starting Android emulator: $avd_name"
    # Quick Boot snapshots can leave the guest visible but permanently offline in ADB.
    # Prefer a deterministic cold boot for automated run and debug workflows.
    # Emulator 37.1.11 can crash its host-side netsimd process during startup on
    # macOS. MegaProxy does not need simulated Wi-Fi/UWB radio packet exchange;
    # Android's regular virtual network remains available with these disabled.
    emulator_options=(
        "@$avd_name"
        -gpu auto
        -no-snapshot-load
        -no-snapshot-save
        -feature -WiFiPacketStream
        -feature -Uwb
        -feature -Nfc
        -netsim-args "--no-test-beacons --no-cli-ui --no-web-ui"
    )
    nohup emulator "${emulator_options[@]}" </dev/null >"$emulator_log" 2>&1 &
fi

echo "Waiting for Android emulator transport"
emulator_online=false
for _ in {1..120}; do
    if [[ "$(adb -e get-state 2>/dev/null || true)" == "device" ]]; then
        emulator_online=true
        break
    fi
    sleep 1
done

if [[ "$emulator_online" != "true" ]]; then
    echo "Android emulator did not become available in ADB within 120 seconds" >&2
    echo "Emulator log: ${emulator_log:-${TMPDIR:-/tmp}/megaproxy-emulator.log}" >&2
    exit 1
fi

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
