#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
local_metadata="$project_dir/.fdroid.yml"
temporary_metadata="$(mktemp)"
trap 'rm -f "$temporary_metadata"' EXIT

upstream_url="https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/net.megaproxy487.yml"
pending_url="https://gitlab.com/andre487/fdroiddata/-/raw/net.megaproxy487/metadata/net.megaproxy487.yml"

if curl --fail --silent --show-error --location "$upstream_url" > "$temporary_metadata"; then
  metadata_url="$upstream_url"
elif curl --fail --silent --show-error --location "$pending_url" > "$temporary_metadata"; then
  metadata_url="$pending_url"
else
  echo "Unable to download MegaProxy metadata from fdroiddata or its pending fork." >&2
  exit 1
fi

ruby -ryaml -e '
  keys = %w[
    AllowedAPKSigningKeys Builds AutoUpdateMode UpdateCheckMode
    CurrentVersion CurrentVersionCode
  ]
  local = YAML.safe_load(File.read(ARGV[0]), permitted_classes: [], aliases: false)
  remote = YAML.safe_load(File.read(ARGV[1]), permitted_classes: [], aliases: false)
  local_recipe = local.select { |key, _| keys.include?(key) }
  remote_recipe = remote.select { |key, _| keys.include?(key) }
  exit 0 if local_recipe == remote_recipe

  warn "The F-Droid recipe is out of sync with #{ARGV[2]}."
  warn "Update metadata/net.megaproxy487.yml in fdroiddata after merging the app change."
  warn "Local recipe:  #{local_recipe.inspect}"
  warn "Remote recipe: #{remote_recipe.inspect}"
  exit 1
' "$local_metadata" "$temporary_metadata" "$metadata_url"

echo "F-Droid metadata matches $metadata_url"
