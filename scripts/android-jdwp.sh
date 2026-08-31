#!/usr/bin/env bash
set -euo pipefail

package_name="net.megaproxy487"
component_name="$package_name/.MainActivity"
debug_port="8700"
jdwp_pid_file="${TMPDIR:-/tmp}/megaproxy-jdwp-session.pid"

adb -e wait-for-device
adb -e shell am force-stop "$package_name"
adb -e shell am set-debug-app -w "$package_name"
adb -e shell am start -D -n "$component_name" >/dev/null

app_pid=""
for _ in {1..50}; do
    app_pid="$(adb -e shell pidof -s "$package_name" 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$app_pid" ]]; then
        break
    fi
    sleep 0.1
done

if [[ -z "$app_pid" ]]; then
    echo "MegaProxy process did not start" >&2
    exit 1
fi

jdwp_snapshot="$(mktemp "${TMPDIR:-/tmp}/megaproxy-jdwp.XXXXXX")"
jdwp_tracker_pid=""
cleanup_jdwp_tracker() {
    if [[ -n "$jdwp_tracker_pid" ]]; then
        kill "$jdwp_tracker_pid" >/dev/null 2>&1 || true
        wait "$jdwp_tracker_pid" >/dev/null 2>&1 || true
    fi
    rm -f "$jdwp_snapshot"
}
trap cleanup_jdwp_tracker EXIT

adb -e jdwp >"$jdwp_snapshot" &
jdwp_tracker_pid="$!"

jdwp_ready=false
for _ in {1..100}; do
    if grep -Fxq "$app_pid" "$jdwp_snapshot"; then
        jdwp_ready=true
        break
    fi
    sleep 0.1
done

if [[ "$jdwp_ready" != "true" ]]; then
    echo "Process $app_pid did not register a JDWP endpoint" >&2
    echo "Registered JDWP processes:" >&2
    cat "$jdwp_snapshot" >&2
    exit 1
fi

cleanup_jdwp_tracker
trap - EXIT

adb -e forward --remove "tcp:$debug_port" >/dev/null 2>&1 || true
adb -e forward "tcp:$debug_port" "jdwp:$app_pid" >/dev/null

forward_entry="$(adb -e forward --list | awk -v port="tcp:$debug_port" '$2 == port { print $0 }')"
if [[ "$forward_entry" != *"jdwp:$app_pid"* ]]; then
    echo "ADB did not create the expected JDWP forward" >&2
    adb -e forward --list >&2
    exit 1
fi

if [[ "$(adb -e get-state)" != "device" ]]; then
    echo "Android emulator disconnected before debugger attach" >&2
    exit 1
fi

echo "JDWP ready: net.megaproxy487 pid=$app_pid port=$debug_port"
echo "Forward: $forward_entry"

if [[ "${MEGAPROXY_JDWP_KEEP_ALIVE:-0}" == "1" ]]; then
    printf '%s\n' "$$" >"$jdwp_pid_file"
    cleanup_debug_session() {
        adb -e forward --remove "tcp:$debug_port" >/dev/null 2>&1 || true
        adb -e shell am clear-debug-app >/dev/null 2>&1 || true
        if [[ -f "$jdwp_pid_file" ]] && [[ "$(tr -dc '0-9' <"$jdwp_pid_file")" == "$$" ]]; then
            rm -f "$jdwp_pid_file"
        fi
    }
    trap cleanup_debug_session EXIT INT TERM

    while adb -e get-state >/dev/null 2>&1; do
        sleep 1
    done
fi
