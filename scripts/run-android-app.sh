#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    # VS Code launched from Finder may not inherit the interactive shell environment.
    source "$HOME/.zshrc.extra"
fi

"$project_dir/scripts/start-android-emulator.sh"

(
    cd "$project_dir"
    bundle exec fastlane android debug_artifact
)

adb -e install -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
adb -e shell am start -n net.megaproxy487/.MainActivity
