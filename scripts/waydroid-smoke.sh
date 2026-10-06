#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p dist/waydroid
export XDG_RUNTIME_DIR="${RUNNER_TEMP:?Run on a disposable Linux runner}/waydroid-runtime"
export WAYLAND_DISPLAY=wayland-0
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
weston --backend=headless-backend.so --renderer=pixman --socket="$WAYLAND_DISPLAY" --idle-time=0 > dist/waydroid/weston.log 2>&1 &
weston_pid=$!
cleanup() {
  if [[ -n "${origin:-}" ]]; then
    sudo iptables -D FORWARD -i waydroid0 -d "$origin" -p tcp --dport 8080 -j REJECT || true
  fi
  sudo waydroid shell -- logcat -d > dist/waydroid/logcat.txt 2>&1 || true
  docker logs megaproxy-waydroid-ssh > dist/waydroid/sshd.log 2>&1 || true
  waydroid session stop || true
  sudo waydroid container stop || true
  docker rm -f megaproxy-waydroid-ssh megaproxy-waydroid-origin >/dev/null 2>&1 || true
  docker network rm megaproxy-waydroid >/dev/null 2>&1 || true
  pulseaudio --kill || true
  kill "$weston_pid" 2>/dev/null || true
}
trap cleanup EXIT
for _ in {1..30}; do
  [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break
  sleep 1
done
[[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]
# Waydroid bind-mounts the audio socket even in a headless session.
pulseaudio --start --exit-idle-time=-1
[[ -S "$XDG_RUNTIME_DIR/pulse/native" ]]
sudo systemctl start waydroid-container
waydroid session start > dist/waydroid/session.log 2>&1 &
session_pid=$!
for _ in {1..90}; do
  if ! kill -0 "$session_pid" 2>/dev/null; then
    cat dist/waydroid/session.log >&2
    exit 1
  fi
  if [[ "$(timeout 10s sudo waydroid shell -- getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
[[ "$(sudo waydroid shell -- getprop sys.boot_completed | tr -d '\r')" == 1 ]]
sudo waydroid shell -- getprop > dist/waydroid/android-properties.txt
waydroid app install app/build/outputs/apk/debug/app-debug.apk
waydroid app install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sudo waydroid shell -- pm grant net.megaproxy487 android.permission.POST_NOTIFICATIONS
sudo waydroid shell -- appops set net.megaproxy487 ACTIVATE_VPN allow
sudo waydroid shell -- dumpsys deviceidle whitelist +net.megaproxy487

server=$(ip -o -4 addr show waydroid0 | awk '{print $4}' | cut -d/ -f1)
[[ -n "$server" ]]
docker build -t megaproxy-waydroid-fixture native/integration
docker network create --internal megaproxy-waydroid
export TEST_PASSWORD
TEST_PASSWORD=$(openssl rand -hex 16)
docker run -d --name megaproxy-waydroid-ssh --network megaproxy-waydroid -p "$server:2222:2222" -e TEST_PASSWORD megaproxy-waydroid-fixture
docker run -d --name megaproxy-waydroid-origin --network megaproxy-waydroid megaproxy-waydroid-fixture python3 /fixture/origin.py
origin=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' megaproxy-waydroid-origin)
for _ in {1..30}; do
  if docker exec megaproxy-waydroid-ssh test -f /etc/ssh/ssh_host_ed25519_key.pub; then break; fi
  sleep 1
done
fingerprint=$(docker exec megaproxy-waydroid-ssh ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{print $2}')
sudo iptables -I FORWARD 1 -i waydroid0 -d "$origin" -p tcp --dport 8080 -j REJECT
sudo waydroid shell -- am instrument -w -r   -e server "$server" -e origin "$origin" -e password "$TEST_PASSWORD" -e fingerprint "$fingerprint"   net.megaproxy487.test/net.megaproxy487.WaydroidSmokeInstrumentation   | tee dist/waydroid/instrumentation.txt
grep -q 'INSTRUMENTATION_RESULT: smoke=passed' dist/waydroid/instrumentation.txt
sudo waydroid shell -- screencap -p /sdcard/waydroid-smoke.png
sudo waydroid shell -- cat /sdcard/waydroid-smoke.png > dist/waydroid/screenshot.png
