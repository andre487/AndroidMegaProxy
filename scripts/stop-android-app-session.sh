#!/usr/bin/env bash
set -euo pipefail

package_name="dev.megaproxy.app"
debug_port="8700"
jdwp_pid_file="${TMPDIR:-/tmp}/megaproxy-jdwp-session.pid"

if [[ -f "$jdwp_pid_file" ]]; then
    jdwp_session_pid="$(tr -dc '0-9' <"$jdwp_pid_file")"
    jdwp_session_command="$(ps -p "$jdwp_session_pid" -o command= 2>/dev/null || true)"
    if [[ -n "$jdwp_session_pid" ]] && [[ "$jdwp_session_command" == *"android-jdwp.sh"* ]] && kill -0 "$jdwp_session_pid" >/dev/null 2>&1; then
        echo "Stopping previous MegaProxy JDWP session: $jdwp_session_pid"
        kill "$jdwp_session_pid" >/dev/null 2>&1 || true
        for _ in {1..20}; do
            kill -0 "$jdwp_session_pid" >/dev/null 2>&1 || break
            sleep 0.1
        done
    fi
    rm -f "$jdwp_pid_file"
fi

if adb -e get-state >/dev/null 2>&1; then
    adb -e forward --remove "tcp:$debug_port" >/dev/null 2>&1 || true
    adb -e shell am clear-debug-app >/dev/null 2>&1 || true
    adb -e shell am force-stop "$package_name" >/dev/null 2>&1 || true
fi
