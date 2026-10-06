#!/usr/bin/env python3
"""Run real Android VPN/Keystore/SAF tests on one disposable API 26 or 35 emulator."""

import argparse
import base64
import contextlib
import http.server
import os
import secrets
import shlex
import socket
import socketserver
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "net.megaproxy487"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
GOST = "gogost/gost:3.3.0@sha256:f7a958c451928fbe1b99046d25bd0c9bf42019d4c60a82200f50dc8e617b7580"
TESTS = (
    "VpnDeviceTest#deniedVpnConsentDoesNotStartTunnel",
    "VpnDeviceTest#trafficStopAndRestart",
    "VpnDeviceTest#notificationActionStopsRealService",
    "VpnDeviceTest#stopDuringSshHandshakeCannotReviveVpn",
    "DocumentsDeviceTest#cancelledDocumentSelectionPreservesConfiguration",
    "DocumentsDeviceTest#systemProviderExportAndImportRoundTrip",
)


def command(*args, timeout=300):
    return subprocess.check_output(
        args, text=True, stderr=subprocess.STDOUT, timeout=timeout
    ).strip()


def require_success(output):
    # adb exits zero even when instrumentation crashes or an assertion fails.
    if (
        "OK (1 test)" not in output
        or "FAILURES!!!" in output
        or "INSTRUMENTATION_FAILED" in output
    ):
        raise RuntimeError("Instrumentation did not report exactly one successful test")


@contextlib.contextmanager
def fixture():
    name = "megaproxy-device-" + uuid.uuid4().hex[:12]
    password = secrets.token_hex(16)
    containers = []
    firewall = None
    image = name + ":fixture"
    network_created = False
    image_built = False
    try:
        command("docker", "build", "-t", image, str(ROOT / "native/integration"))
        image_built = True
        command("docker", "network", "create", name)
        network_created = True

        def start(alias, port, server_image, *args):
            container = name + "-" + alias
            containers.append(container)
            options = ["docker", "run", "-d", "--name", container, "--network", name]
            if port:
                options += ["-p", "127.0.0.1::" + port]
            if alias == "ssh":
                options += ["-e", "TEST_PASSWORD=" + password]
            command(*options, server_image, *args)
            return container

        origin = start("origin", "", image, "python3", "/fixture/origin.py")
        origin_ip = command(
            "docker",
            "inspect",
            "--format",
            "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
            origin,
        )
        # Emulator user-mode networking originates in the host OUTPUT chain. Container
        # traffic uses FORWARD, so only the proxies can reach the unpublished origin.
        if sys.platform == "linux":
            firewall = [
                "OUTPUT",
                "-d",
                origin_ip,
                "-p",
                "tcp",
                "--dport",
                "8080",
                "-m",
                "comment",
                "--comment",
                name,
                "-j",
                "REJECT",
            ]
            command("sudo", "-n", "iptables", "-I", *firewall)
        https = start("https", "8443", GOST, "-L", f"http+tls://exit:{password}@:8443")
        ssh = start("ssh", "2222", image)

        def port(container, number):
            address = command("docker", "port", container, number + "/tcp")
            host, value = address.rsplit(":", 1)
            if host != "127.0.0.1":
                raise RuntimeError("Fixture proxy must be published on loopback only")
            deadline = time.monotonic() + 30
            while True:
                try:
                    with socket.create_connection((host, int(value)), timeout=1):
                        return value
                except OSError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Fixture proxy did not start") from None
                    time.sleep(0.1)

        https_port, ssh_port = port(https, "8443"), port(ssh, "2222")
        key = (
            command("docker", "exec", ssh, "cat", "/home/proxy/.ssh/id_ed25519") + "\n"
        )
        fingerprint = command(
            "docker",
            "exec",
            ssh,
            "ssh-keygen",
            "-lf",
            "/etc/ssh/ssh_host_ed25519_key.pub",
        ).split()[1]
        yield {
            "originHost": origin_ip,
            "proxyPort": https_port,
            "proxyPassword": password,
            "sshPort": ssh_port,
            "sshPassword": password,
            "sshKey": base64.b64encode(key.encode()).decode(),
            "sshFingerprint": fingerprint,
        }
    finally:
        # Cleanup is attempted independently so one failed removal cannot leak siblings.
        for container in reversed(containers):
            subprocess.run(
                ["docker", "rm", "-f", container], capture_output=True, timeout=30
            )
        if firewall:
            subprocess.run(
                ["sudo", "-n", "iptables", "-D", *firewall],
                capture_output=True,
                timeout=30,
            )
        if network_created:
            subprocess.run(
                ["docker", "network", "rm", name], capture_output=True, timeout=30
            )
        if image_built:
            subprocess.run(
                ["docker", "image", "rm", image], capture_output=True, timeout=30
            )


@contextlib.contextmanager
def stalled_ssh():
    accepted, release = threading.Event(), threading.Event()

    class Stall(socketserver.BaseRequestHandler):
        def handle(self):
            accepted.set()
            release.wait(60)

    class Control(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/release":
                release.set()
            body = b"yes" if accepted.is_set() else b"no"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    with (
        socketserver.ThreadingTCPServer(("127.0.0.1", 0), Stall) as server,
        http.server.ThreadingHTTPServer(("127.0.0.1", 0), Control) as control,
    ):
        server.daemon_threads = True
        workers = [
            threading.Thread(target=s.serve_forever, daemon=True)
            for s in (server, control)
        ]
        for worker in workers:
            worker.start()
        try:
            yield {
                "stallPort": str(server.server_address[1]),
                "controlPort": str(control.server_address[1]),
            }
        finally:
            release.set()
            server.shutdown()
            control.shutdown()
            for worker in workers:
                worker.join()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, choices=(26, 35), required=True)
    args = parser.parse_args()
    adb_path = Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial or not serial.startswith("emulator-"):
        parser.error("ANDROID_SERIAL must explicitly select a disposable emulator")
    adb = [str(adb_path), "-s", serial]

    def shell(*words):
        return command(*adb, "shell", shlex.join(words), timeout=180)

    if int(shell("getprop", "ro.build.version.sdk")) != args.api:
        parser.error("Connected emulator API does not match --api")
    if shell("getprop", "ro.kernel.qemu") != "1":
        parser.error("Device is not an emulator")
    results = ROOT / "test-results" / f"android-api{args.api}"
    results.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", name=f"Android API {args.api}")
    failures = 0
    command(
        *adb, "install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    )
    command(
        *adb,
        "install",
        "-r",
        str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
    )
    with fixture() as parameters, stalled_ssh() as stalled:
        parameters.update(stalled)
        tests = [(name, None) for name in TESTS]
        if args.api >= 33:
            tests.append(("VpnDeviceTest#deniedNotificationsStillAllowVpn", None))
        tests += [
            ("KeystoreProcessDeviceTest#credentialsSurviveProcessRestart", "seed"),
            ("KeystoreProcessDeviceTest#credentialsSurviveProcessRestart", "verify"),
        ]
        for test, phase in tests:
            label = test.replace("#", ".") + ("." + phase if phase else "")
            case = ET.SubElement(suite, "testcase", name=label)
            started = time.monotonic()
            try:
                shell("am", "force-stop", PACKAGE)
                if phase != "verify":
                    if shell("pm", "clear", PACKAGE) != "Success":
                        raise RuntimeError("Failed to reset disposable app data")
                shell("appops", "set", PACKAGE, "ACTIVATE_VPN", "default")
                if args.api >= 33 and "deniedNotifications" not in test:
                    shell(
                        "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS"
                    )
                shell("logcat", "-c")
                options = [
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "class",
                    PACKAGE + "." + test,
                ]
                for key, value in {
                    **parameters,
                    **({"phase": phase} if phase else {}),
                }.items():
                    options += ["-e", key, value]
                output = shell(*options, RUNNER)
                (results / (label + ".txt")).write_text(output)
                require_success(output)
                print(f"PASS API {args.api}: {label}", flush=True)
            except (RuntimeError, subprocess.SubprocessError) as error:
                failures += 1
                # Tool failures can include command arguments; never publish the key/password.
                message = (
                    str(error)
                    if isinstance(error, RuntimeError)
                    else type(error).__name__
                )
                ET.SubElement(case, "failure", message=message)
                print(f"FAIL API {args.api}: {label}: {message}", flush=True)
                screenshot = subprocess.run(
                    [*adb, "exec-out", "screencap", "-p"],
                    capture_output=True,
                    timeout=15,
                )
                (results / (label + ".png")).write_bytes(screenshot.stdout)
                shell("uiautomator", "dump", "/sdcard/megaproxy-device-window.xml")
                (results / (label + ".xml")).write_text(
                    shell("cat", "/sdcard/megaproxy-device-window.xml")
                )
            finally:
                case.set("time", f"{time.monotonic() - started:.3f}")
                (results / (label + "-logcat.txt")).write_text(
                    shell("logcat", "-d", "-v", "threadtime")
                )
                shell("am", "force-stop", PACKAGE)
                suite.set("tests", str(len(suite)))
                suite.set("failures", str(failures))
                ET.ElementTree(suite).write(
                    results / "junit.xml", encoding="utf-8", xml_declaration=True
                )
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
