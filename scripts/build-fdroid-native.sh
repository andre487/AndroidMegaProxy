#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
gomobile_version="v0.0.0-20260821190718-4776eadac327"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"

export GOPATH="${GOPATH:-$HOME/go}"
export PATH="${GOROOT:+$GOROOT/bin:}$GOPATH/bin:$PATH"

go install "golang.org/x/mobile/cmd/gomobile@$gomobile_version"
go install "golang.org/x/mobile/cmd/gobind@$gomobile_version"
gomobile init

mkdir -p "$project_dir/app/libs"
cd "$project_dir/native"
gomobile bind \
  -target=android \
  -androidapi 26 \
  -trimpath \
  -ldflags='-s -w -buildid=' \
  -o "$project_dir/app/libs/megaproxy.aar" \
  ./mobile
