#!/usr/bin/env bash
set -eu

machine=$(uname -m)
case "$machine" in
    x86_64|amd64) echo x86_64 ;;
    arm64|aarch64) echo arm64-v8a ;;
    *) echo "Unsupported Android emulator host architecture: $machine" >&2; exit 1 ;;
esac
