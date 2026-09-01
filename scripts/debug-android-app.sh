#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    source "$HOME/.zshrc.extra"
fi

echo "Starting MegaProxy JDWP setup"
"$project_dir/scripts/stop-android-app-session.sh"
"$project_dir/scripts/run-android-app.sh"
"$project_dir/scripts/android-jdwp.sh"
