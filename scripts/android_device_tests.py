#!/usr/bin/env python3
"""Run real Android VPN/Keystore/SAF tests on one disposable API 26 or 35 emulator."""

import argparse
import base64
import contextlib
import errno
import os
import re
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
    # Must run before any scenario grants the app system VPN consent.
    "VpnDeviceTest#deniedVpnConsentDoesNotStartTunnel",
    "DnsDeviceTest#resolverModesThroughRealTun",
    "VpnDeviceTest#trafficStopAndRestart",
    "VpnDeviceTest#socks5AuthenticatedTcpUdpStopAndRestart",
    "VpnDeviceTest#socks5AnonymousTcpUdpStopAndRestart",
    "VpnDeviceTest#masqueTrafficStopAndRestart",
    "VpnDeviceTest#httpsPreferenceUsesMasqueThroughTunAndJni",
    "VpnDeviceTest#httpsPreferenceFallsBackWithoutBlockingRecovery",
    "VpnDeviceTest#httpsJumpPreferenceBothNodesSupportHttp3",
    "VpnDeviceTest#httpsJumpPreferenceOnlyFirstSupportsHttp3",
    "VpnDeviceTest#httpsJumpPreferenceOnlyExitSupportsHttp3",
    "VpnDeviceTest#httpsJumpPreferenceNeitherSupportsHttp3",
    "VpnDeviceTest#masqueFirefoxTraffic",
    "VpnDeviceTest#masqueRandomizedTraffic",
    "VpnDeviceTest#masqueCustomTraffic",
    "VpnDeviceTest#masqueChromeOversizedUdpPreservesFlow",
    "VpnDeviceTest#masqueFirefoxOversizedUdpPreservesFlow",
    "VpnDeviceTest#masqueWrongCredentialsRejectedAndCorrected",
    "VpnDeviceTest#masqueUntrustedCertificateRejected",
    "VpnDeviceTest#masqueSplitRoutingIncludesAndExcludesApplication",
    "VpnDeviceTest#masqueCustomFailoverToHttps",
    "VpnDeviceTest#notificationActionStopsRealService",
    "VpnDeviceTest#stopDuringSshHandshakeCannotReviveVpn",
    "DocumentsDeviceTest#subscriptionSnapshotsUseDeviceKeystoreAndBundledSchema",
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


def finish_case(shell, results, label, case, suite):
    error_message = None
    evidence = (
        (("anr", ("dumpsys", "dropbox", "--print", "data_app_anr")),)
        if case.find("failure") is not None
        else ()
    )
    for name, words in (
        ("logcat", ("logcat", "-d", "-v", "threadtime")),
        *evidence,
        ("force-stop", ("am", "force-stop", PACKAGE)),
    ):
        try:
            output = shell(*words)
        except subprocess.SubprocessError as error:
            output = getattr(error, "output", None) or ""
            if isinstance(output, bytes):
                output = output.decode(errors="replace")
            message = f"{name} failed ({type(error).__name__}, exit {getattr(error, 'returncode', 'unknown')})"
            ET.SubElement(case, "failure", message=message)
            error_message = error_message or message
        if name != "force-stop" or error_message:
            (results / (label + f"-{name}.txt")).write_text(output)
    suite.set("tests", str(len(suite)))
    suite.set("failures", str(sum(c.find("failure") is not None for c in suite)))
    suite.set("time", f"{sum(float(c.get('time', '0')) for c in suite):.3f}")
    ET.ElementTree(suite).write(
        results / "junit.xml", encoding="utf-8", xml_declaration=True
    )
    if error_message:
        raise RuntimeError(error_message + "; see collected evidence")


def begin_case(shell, api, label):
    shell("am", "force-stop", PACKAGE)
    if api >= 33:
        operation = "revoke" if "deniedNotifications" in label else "grant"
        shell("pm", operation, PACKAGE, "android.permission.POST_NOTIFICATIONS")
    deadline = time.monotonic() + 15
    while True:
        process = shell("sh", "-c", f"pidof {PACKAGE} || true")
        activities = shell("dumpsys", "activity", "activities")
        if not process and not re.search(
            r"ActivityRecord\{[^\n]* net\.megaproxy487(?:\.test)?/", activities
        ):
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Previous Android process or activities did not finish")
        time.sleep(0.1)
    # Start a fresh buffer only after the previous instrumentation process is gone.
    # This works on API 26 too, without relying on date's fractional-second support.
    shell("logcat", "-c")
    shell("log", "-t", "MegaProxyDeviceTest", "BEGIN " + label)


def dual_protocol_port():
    # Only allocation collisions are retried, before containers or tests start.
    for attempt in range(8):
        with (
            socket.socket() as tcp,
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp,
        ):
            tcp.bind(("127.0.0.1", 0))
            number = tcp.getsockname()[1]
            try:
                udp.bind(("127.0.0.1", number))
            except OSError as error:
                if error.errno != errno.EADDRINUSE or attempt == 7:
                    raise
                continue
            return number


class DirectDnsFixture(socketserver.BaseRequestHandler):
    def handle(self):
        query, sock = self.request
        reply = (
            query[:2]
            + b"\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00"
            + query[12:]
            + b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\xcb\x00\x71\x2a"
        )
        sock.sendto(reply, self.client_address)


@contextlib.contextmanager
def fixture():
    name = "megaproxy-device-" + uuid.uuid4().hex[:12]
    password = secrets.token_hex(16)
    containers = []
    firewall = []
    image = name + ":fixture"
    network_created = False
    image_built = False
    direct_dns = socketserver.UDPServer(("127.0.0.1", 0), DirectDnsFixture)
    threading.Thread(target=direct_dns.serve_forever, daemon=True).start()
    blackhole = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        blackhole.bind(("127.0.0.1", 0))
        command("docker", "build", "-t", image, str(ROOT / "native/integration"))
        image_built = True
        command("docker", "network", "create", name)
        network_created = True

        def start(alias, port, server_image, *args):
            container = name + "-" + alias
            containers.append(container)
            options = ["docker", "run", "-d", "--name", container, "--network", name]
            if port == "8443/both":
                number = dual_protocol_port()
                options += [
                    "-p",
                    f"127.0.0.1:{number}:8443/tcp",
                    "-p",
                    f"127.0.0.1:{number}:8443/udp",
                ]
            elif port:
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
            for protocol, number in (("tcp", "8080"), ("udp", "8081")):
                rule = [
                    "OUTPUT",
                    "-d",
                    origin_ip,
                    "-p",
                    protocol,
                    "--dport",
                    number,
                    "-m",
                    "comment",
                    "--comment",
                    name,
                    "-j",
                    "REJECT",
                ]
                command("sudo", "-n", "iptables", "-I", *rule)
                firewall.append(rule)
        socks_auth = start(
            "socks-auth",
            "1080",
            GOST,
            "-L",
            f"socks5://exit:{password}@:1080?udp=true&udpBufferSize=65535",
        )
        socks_anonymous = start(
            "socks-anonymous",
            "1080",
            GOST,
            "-L",
            "socks5://:1080?udp=true&udpBufferSize=65535",
        )
        https = start("https", "8443", GOST, "-L", f"http+tls://exit:{password}@:8443")
        masque = start(
            "masque",
            "8443/udp",
            GOST,
            "-L",
            f"masque+http3://exit:{password}@:8443?enableDatagrams=true",
        )
        masque_address = command("docker", "port", masque, "8443/udp")
        masque_host, masque_port = masque_address.rsplit(":", 1)
        if masque_host != "127.0.0.1":
            raise RuntimeError("MASQUE fixture must be published on loopback only")
        fallback = start(
            "https-fallback", "8443", GOST, "-L", f"http+tls://exit:{password}@:8443"
        )
        dual_jump = start(
            "jump-dual",
            "8443/both",
            GOST,
            "-L",
            f"http2://jump:{password}@:8443",
            "-L",
            f"masque+http3://jump:{password}@:8443?enableDatagrams=true",
        )
        dual_exit = start(
            "exit-dual",
            "8443/both",
            GOST,
            "-L",
            f"http2://exit:{password}@:8443",
            "-L",
            f"masque+http3://exit:{password}@:8443?enableDatagrams=true",
        )
        tcp_jump = start(
            "jump-tcp", "8443", GOST, "-L", f"http2://jump:{password}@:8443"
        )
        ssh = start("ssh", "2222", image)

        def port(container, number):
            address = command("docker", "port", container, number + "/tcp")
            host, value = address.rsplit(":", 1)
            if host != "127.0.0.1":
                raise RuntimeError("Fixture proxy must be published on loopback only")
            deadline = time.monotonic() + 30
            while True:
                try:
                    with socket.create_connection(
                        (host, int(value)), timeout=1
                    ) as probe:
                        # VM port forwarders can accept before sshd has generated its keys.
                        if container != ssh or probe.recv(4) == b"SSH-":
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
            "directDnsPort": str(direct_dns.server_address[1]),
            "proxyPort": https_port,
            "socksPort": port(socks_auth, "1080"),
            "socksAnonymousPort": port(socks_anonymous, "1080"),
            "httpsFallbackPort": port(fallback, "8443"),
            "dualJumpPort": port(dual_jump, "8443"),
            "tcpJumpPort": port(tcp_jump, "8443"),
            "dualExitHost": command(
                "docker",
                "inspect",
                "--format",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                dual_exit,
            ),
            "tcpExitHost": command(
                "docker",
                "inspect",
                "--format",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                https,
            ),
            "masquePort": masque_port,
            "blackholeMasquePort": str(blackhole.getsockname()[1]),
            "proxyPassword": password,
            "sshPort": ssh_port,
            "sshPassword": password,
            "sshKey": base64.b64encode(key.encode()).decode(),
            "sshFingerprint": fingerprint,
        }
    finally:
        direct_dns.shutdown()
        direct_dns.server_close()
        blackhole.close()
        # Cleanup is attempted independently so one failed removal cannot leak siblings.
        for container in reversed(containers):
            subprocess.run(
                ["docker", "rm", "-f", container], capture_output=True, timeout=30
            )
        for rule in reversed(firewall):
            subprocess.run(
                ["sudo", "-n", "iptables", "-D", *rule],
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
def stalled_ssh(shell):
    release = threading.Event()
    prefix = "/data/local/tmp/megaproxy-device-" + uuid.uuid4().hex
    accepted_path, release_path = prefix + "-accepted", prefix + "-release"

    class Stall(socketserver.BaseRequestHandler):
        def handle(self):
            shell("touch", accepted_path)
            deadline = time.monotonic() + 60
            while not release.wait(0.1) and time.monotonic() < deadline:
                if (
                    shell("sh", "-c", f"if test -f {release_path}; then echo yes; fi")
                    == "yes"
                ):
                    break

    with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Stall) as server:
        server.daemon_threads = True
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield {
                "stallPort": str(server.server_address[1]),
                "sshAccepted": accepted_path,
                "sshRelease": release_path,
            }
        finally:
            release.set()
            server.shutdown()
            worker.join()
            # The disposable emulator is discarded on failure; preserve the cause.
            if sys.exc_info()[0] is None:
                shell("rm", "-f", accepted_path, release_path)


def prepare_emulator(shell, api):
    # Fixtures are private: public captive-portal probes must not decide whether
    # Android supplies their underlying network. Reconnect to apply the setting.
    previous_default = None
    shell("settings", "put", "global", "captive_portal_mode", "0")
    # The API 26 svc Wi-Fi client is killed (137), including on x86_64 CI.
    # Keep its existing connection; readiness below is required on both APIs.
    if api >= 35:
        previous_default = re.search(
            r"Active default network: (\d+)", shell("dumpsys", "connectivity")
        )
        shell("svc", "wifi", "disable")
        deadline = time.monotonic() + 30
        while "Wi-Fi is disabled" not in shell("dumpsys", "wifi"):
            if time.monotonic() >= deadline:
                raise RuntimeError("Disposable emulator Wi-Fi did not disconnect")
            time.sleep(0.1)
        shell("svc", "wifi", "enable")
    # These scenarios test proxy failover, not Android network handover.
    shell("svc", "data", "disable")
    # Unused image apps can obscure tests with crash/ANR dialogs. MegaProxy's
    # crashes remain visible; these changes apply only to disposable emulators.
    package = {
        26: "com.google.android.apps.messaging",
        35: "com.google.android.apps.nexuslauncher",
    }[api]
    shell("am", "force-stop", package)
    shell("pm", "disable-user", "--user", "0", package)

    deadline = time.monotonic() + 30
    while True:
        state = shell("dumpsys", "connectivity")
        default = re.search(r"Active default network: (\d+)", state)
        if (
            default
            and (not previous_default or default[1] != previous_default[1])
            and any(
                line.lstrip().startswith("NetworkAgentInfo{")
                and f"network{{{default[1]}}}" in line
                and "Transports: WIFI" in line
                and re.search(r"Capabilities:[^\]]*\bVALIDATED\b", line)
                for line in state.splitlines()
            )
        ):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(
                "Disposable emulator has no validated default Wi-Fi network"
            )
        time.sleep(0.1)


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
    if shell("getprop", "sys.boot_completed") != "1":
        parser.error(
            "Wait for the emulator to finish booting before running device_tests"
        )
    if shell("getprop", "ro.kernel.qemu") != "1":
        parser.error("Device is not an emulator")
    results = ROOT / "test-results" / f"android-api{args.api}"
    results.mkdir(parents=True, exist_ok=True)
    try:
        prepare_emulator(shell, args.api)
    except (RuntimeError, subprocess.SubprocessError):
        for service in ("connectivity", "wifi"):
            (results / f"preflight-{service}.txt").write_text(shell("dumpsys", service))
        raise
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
    shell("am", "force-stop", PACKAGE)
    if shell("pm", "clear", PACKAGE) != "Success":
        raise RuntimeError("Failed to reset disposable app data")
    with fixture() as parameters, stalled_ssh(shell) as stalled:
        parameters.update(stalled)
        # Verify the emulator-to-host leg too, before any VPN or test starts.
        try:
            banner = shell(
                "sh",
                "-c",
                # Older toybox can exit on stdin EOF before a delayed SSH banner.
                f"sleep 3 | toybox nc -w 3 -q 1 10.0.2.2 {int(parameters['sshPort'])}",
            )
            if not banner.startswith("SSH-"):
                raise RuntimeError("Local SSH fixture did not send its banner")
        except (RuntimeError, subprocess.SubprocessError):
            (results / "preflight-connectivity.txt").write_text(
                shell("dumpsys", "connectivity")
            )
            raise RuntimeError(
                "Disposable emulator cannot reach the local SSH fixture"
            ) from None
        tests = [(name, None) for name in TESTS]
        if args.api >= 29:
            tests.append(
                ("VpnDeviceTest#strictSourceValidatedTcpUdpStopAndRestart", None)
            )
        if args.api >= 33:
            tests.append(("VpnDeviceTest#deniedNotificationsStillAllowVpn", None))
        tests += [
            ("KeystoreProcessDeviceTest#credentialsSurviveProcessRestart", "seed"),
            ("KeystoreProcessDeviceTest#credentialsSurviveProcessRestart", "verify"),
        ]
        for test, phase in tests:
            label = test.replace("#", ".") + ("." + phase if phase else "")
            class_name, method = test.split("#", 1)
            case = ET.SubElement(
                suite,
                "testcase",
                classname=PACKAGE + "." + class_name,
                name=method + ("." + phase if phase else ""),
            )
            started = time.monotonic()
            try:
                begin_case(shell, args.api, label)
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
                if isinstance(error, subprocess.CalledProcessError):
                    (results / (label + ".txt")).write_text(error.output or "")
                message = (
                    str(error)
                    if isinstance(error, RuntimeError)
                    else type(error).__name__
                )
                ET.SubElement(case, "failure", message=message)
                print(f"FAIL API {args.api}: {label}: {message}", flush=True)
                for extension in ("png", "xml"):
                    destination = results / (label + "." + extension)
                    evidence = f"/sdcard/Android/data/{PACKAGE}/files/device-failure.{extension}"
                    pulled = subprocess.run(
                        [*adb, "pull", evidence, str(destination)],
                        capture_output=True,
                        timeout=15,
                    )
                    if pulled.returncode:
                        print(f"Failure {extension} evidence unavailable", flush=True)
            finally:
                case.set("time", f"{time.monotonic() - started:.3f}")
                finish_case(shell, results, label, case, suite)
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
