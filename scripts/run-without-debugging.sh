#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$HOME/.zshrc.extra" ]]; then
    # VS Code launched from Finder may not inherit the interactive shell environment.
    source "$HOME/.zshrc.extra"
fi

"$project_dir/scripts/stop-android-app-session.sh"

exec "$project_dir/scripts/run-android-app.sh"
