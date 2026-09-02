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

android_abi="${MEGAPROXY_ANDROID_ABI:-}"
case "$android_abi" in
  "") gomobile_target="android" ;;
  armeabi-v7a) gomobile_target="android/arm" ;;
  arm64-v8a) gomobile_target="android/arm64" ;;
  x86) gomobile_target="android/386" ;;
  x86_64) gomobile_target="android/amd64" ;;
  *)
    echo "Unsupported MEGAPROXY_ANDROID_ABI: $android_abi" >&2
    exit 1
    ;;
esac

mkdir -p "$project_dir/app/libs"
cd "$project_dir/native"
gomobile bind \
  -target="$gomobile_target" \
  -androidapi 26 \
  -trimpath \
  -ldflags='-s -w -buildid=' \
  -o "$project_dir/app/libs/megaproxy.aar" \
  ./mobile
