#!/usr/bin/env bash
set -euo pipefail

package_name="dev.megaproxy.app"
adb -e wait-for-device

app_pid="$(adb -e shell pidof -s "$package_name" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$app_pid" ]]; then
    echo "MegaProxy is not running; launch it before opening app Logcat" >&2
    exit 1
fi

exec adb -e logcat --pid="$app_pid"
