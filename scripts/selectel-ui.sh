#!/usr/bin/env bash
set -euo pipefail
# Source only Selectel credentials; release signing must never enter UI test jobs.
if [[ -z "${GITHUB_ACTIONS:-}" ]]; then
  config_file="${MEGAPROXY_SELECTEL_ENV:-$HOME/.config/megaproxy/selectel.env}"
  if [[ -f "$config_file" ]]; then
    set -a
    source "$config_file"
    set +a
  fi
fi
exec python3 "$(dirname "$0")/selectel_ui.py" "$@"
