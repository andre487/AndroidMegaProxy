#!/usr/bin/env python3
"""Selectel device lease + Android instrumentation, using only Python's standard library.

State contains device/slot IDs, never credentials. Only devices created by this run
are eligible for release. APKs must be built before acquiring a paid device.
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.selectel.ru/mobfarm/api"
AUTH = "https://cloud.api.selcloud.ru/identity/v3/auth/tokens"
APP = "net.megaproxy487"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"


class ApiError(RuntimeError):
    def __init__(self, method, path, status):
        super().__init__(f"Selectel {method} {path}: HTTP {status}")
        self.status = status


class Client:
    def __init__(self):
        names = [
            "SELECTEL_USERNAME",
            "SELECTEL_PASSWORD",
            "SELECTEL_ACCOUNT_ID",
            "SELECTEL_PROJECT_ID",
        ]
        if any(not os.environ.get(n) for n in names):
            raise RuntimeError("Required variables: " + ", ".join(names))
        self.project = os.environ["SELECTEL_PROJECT_ID"]
        payload = {
            "auth": {
                "identity": {
                    "methods": ["password"],
                    "password": {
                        "user": {
                            "name": os.environ["SELECTEL_USERNAME"],
                            "password": os.environ["SELECTEL_PASSWORD"],
                            "domain": {"name": os.environ["SELECTEL_ACCOUNT_ID"]},
                        }
                    },
                },
                "scope": {"project": {"id": self.project}},
            }
        }
        request = urllib.request.Request(
            AUTH,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                self.token = response.headers["X-Subject-Token"]
        except urllib.error.HTTPError as e:
            raise ApiError("POST", "auth", e.code) from None
        if not self.token:
            raise RuntimeError("Selectel did not return an IAM token")

    def request(self, method, path, body=None):
        request = urllib.request.Request(
            API + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "X-Auth-Token": self.token},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            # Never include response bodies: auth failures may reflect request data.
            raise ApiError(method, path, e.code) from None


def write_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(state, indent=2))
    os.chmod(temp, 0o600)
    temp.replace(path)


def read_state(path):
    return json.loads(path.read_text())


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = str(Path(sdk) / "platform-tools/adb") if sdk else shutil.which("adb")
    if not adb or not Path(adb).is_file():
        raise RuntimeError("ADB is required (ANDROID_HOME/platform-tools/adb or PATH)")
    return adb


def adb_env(state_path):
    # ADB reads its primary key from ~/.android/adbkey, independently of Android
    # SDK preference paths. Use that same identity when registering with Selectel.
    env = os.environ.copy()
    for name in (
        "ANDROID_USER_HOME",
        "ANDROID_PREFS_ROOT",
        "ADB_VENDOR_KEYS",
        "ADB_SERVER_SOCKET",
    ):
        env.pop(name, None)
    return env


def adb_public_key(path):
    key = Path.home() / ".android/adbkey"
    if not key.is_file():
        # Let ADB create its normal identity; never overwrite an existing key.
        adb(path, "start-server")
    public_key = adb(path, "pubkey", str(key)).strip()
    if not public_key:
        raise RuntimeError("ADB did not return its primary public key")
    return public_key


def adb(state_path, *args, timeout=30, check=True, include_stderr=False):
    port = str(os.environ.get("SELECTEL_ADB_PORT", "5038"))
    result = subprocess.run(
        [adb_path(), "-P", port, *args],
        env=adb_env(state_path),
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if check and result.returncode:
        raise RuntimeError(f"ADB command failed: {args[0]} (exit {result.returncode})")
    return result.stdout + (result.stderr if include_stderr else "")


def connect_device(path, endpoint):
    # Selectel documents that connect may report authentication failure even on
    # success. Keep the transport alive and check its actual state afterwards.
    print("Waiting for remote ADB (maximum 90 seconds)", flush=True)
    deadline = time.monotonic() + 90
    try:
        adb(path, "connect", endpoint, timeout=30, check=False, include_stderr=True)
    except subprocess.TimeoutExpired:
        pass  # The server may still be establishing the transport.
    diagnostic = "no response"
    while time.monotonic() < deadline:
        try:
            devices = adb(
                path, "devices", "-l", timeout=10, check=False, include_stderr=True
            )
            state = next(
                (
                    line.split()[1]
                    for line in devices.splitlines()
                    if len(line.split()) >= 2 and line.split()[0] == endpoint
                ),
                "absent",
            )
            if state == "device":
                return
            if state != diagnostic:
                print(f"Remote ADB state: {state}", flush=True)
            diagnostic = state
        except subprocess.TimeoutExpired:
            diagnostic = "state query timed out"
        time.sleep(3)
    raise RuntimeError("Remote ADB did not connect within 90 seconds: " + diagnostic)


def wait_until_ready(client, serial):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        info = client.request("GET", "/v3/devices/" + serial)
        # API status 3 is the operational device status; ownership is separate.
        # See Selectel's device settings preconditions and live GET device format.
        if (
            info.get("status") == 3
            and info.get("ready") is True
            and info.get("present") is True
        ):
            return
        time.sleep(5)
    raise RuntimeError("Device did not become ready within 180 seconds")


def device_matrix(profile):
    if profile not in ("required", "additional"):
        raise RuntimeError("Device profile must be required or additional")
    catalog = json.loads((ROOT / "config/selectel-devices.json").read_text())
    devices = catalog["devices"]
    if not devices:
        raise RuntimeError("The fixed device catalog is empty")
    ids = set()
    for device in devices:
        if not re.fullmatch(r"[a-z0-9-]+", device["id"]) or device["id"] in ids:
            raise RuntimeError("Invalid or duplicate device matrix ID")
        if (
            device["abi"] not in ("arm64-v8a", "armeabi-v7a")
            or not str(device["api"]).isdigit()
        ):
            raise RuntimeError("Unsupported device matrix entry")
        ids.add(device["id"])
    required = [d for d in devices if d["id"] == catalog["required_device"]]
    if len(required) != 1:
        raise RuntimeError(
            "The required device must occur exactly once in the fixed catalog"
        )
    if profile == "required":
        return required
    return [device for device in devices if device["id"] != required[0]["id"]]


def select_configuration(device):
    for name, key in [
        ("SELECTEL_DEVICE_MODEL", "model"),
        ("SELECTEL_ANDROID_API", "api"),
        ("SELECTEL_DEVICE_ABI", "abi"),
        ("SELECTEL_DEVICE_MANUFACTURER", "manufacturer"),
    ]:
        os.environ[name] = str(device[key])


def release_all(client, root):
    errors = []
    for path in sorted(root.rglob("lease.json")):
        try:
            release(client, path)
        except Exception as error:
            errors.append(f"{path}: {error}")
    if errors:
        raise RuntimeError("; ".join(errors))


def run_matrix(client, state_path, profile):
    outcomes = []
    for device in device_matrix(profile):
        select_configuration(device)
        os.environ["SELECTEL_RESULT_ID"] = device["id"]
        path = state_path.parent / device["id"] / "lease.json"
        print("Configuration: " + device["id"], flush=True)
        outcome = {"device": device["id"], "status": "passed"}
        try:
            acquire(client, path)
            run_tests(path)
        except Exception as error:
            outcome.update(
                status="failed",
                error=(
                    str(error)
                    if isinstance(error, RuntimeError)
                    else type(error).__name__
                ),
            )
        finally:
            cleanup_error = None
            try:
                release(client, path)
            except Exception as error:
                cleanup_error = error
                outcome.update(status="failed", cleanup_error=type(error).__name__)
        outcomes.append(outcome)
        report = ROOT / "app/build/reports/selectel/matrix.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(outcomes, indent=2))
        if cleanup_error is not None:
            raise RuntimeError(
                "Matrix stopped after cleanup failed; run selectel_release"
            ) from cleanup_error
    if any(result["status"] != "passed" for result in outcomes):
        raise RuntimeError("Some fixed device configurations failed; see matrix.json")


class DeviceUnavailable(RuntimeError):
    pass


def choose_device(available):
    default = device_matrix("required")[0]
    sdk = os.environ.get("SELECTEL_ANDROID_API", default["api"])
    model = os.environ.get("SELECTEL_DEVICE_MODEL", default["model"])
    abi = os.environ.get("SELECTEL_DEVICE_ABI", default["abi"])
    manufacturer = os.environ.get("SELECTEL_DEVICE_MANUFACTURER", "")
    choices = [
        d
        for d in available
        if d.get("platform") == "Android"
        and str(d.get("sdk")) == sdk
        and d.get("abi") == abi
        and (not manufacturer or d.get("manufacturer") == manufacturer)
        and d.get("count", 0) > 0
        and (not model or d.get("marketName") == model)
    ]
    if not choices:
        raise DeviceUnavailable(
            f"Unavailable configuration: {manufacturer} {model}, API {sdk}, {abi}; no lease created"
        )
    return sorted(choices, key=lambda d: d["marketName"])[0]


def wait_for_device(client):
    raw_timeout = os.environ.get("SELECTEL_DEVICE_WAIT_SECONDS", "600")
    if (
        not raw_timeout.isascii()
        or not raw_timeout.isdecimal()
        or not 0 <= int(raw_timeout) <= 900
    ):
        raise RuntimeError(
            "SELECTEL_DEVICE_WAIT_SECONDS must be an integer from 0 to 900"
        )
    timeout = int(raw_timeout)
    deadline = time.monotonic() + timeout
    while True:
        try:
            return choose_device(client.request("GET", "/v3/devices/available"))
        except DeviceUnavailable as error:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DeviceUnavailable(
                    f"{error}; availability wait expired ({timeout}s)"
                ) from None
            delay = min(30, remaining)
            print(
                f"{error}; checking again in {delay:.0f}s ({remaining:.0f}s remaining)",
                flush=True,
            )
            time.sleep(delay)


def acquire(client, path):
    if path.exists() and not read_state(path).get("released"):
        raise RuntimeError(
            "An unreleased lease journal exists; run selectel_release first"
        )
    for apk in [
        ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]:
        if not apk.is_file():
            raise RuntimeError("Build UI test APKs before renting a device")
    public_key = adb_public_key(path)
    model = wait_for_device(client)
    existing = client.request("GET", "/v3/devices")
    state = {
        "project": client.project,
        "created_at": time.time(),
        "devices": [],
        "released": False,
        "baseline": [d["serial"] for d in existing],
    }
    write_state(path, state)
    filters = {
        k: model[k] for k in ["manufacturer", "marketName", "sdk", "platform", "abi"]
    }
    filters["count"] = 1
    print(
        f"Renting one {model['marketName']} (API {model['sdk']}), billing=minutes",
        flush=True,
    )
    # Never retry this non-idempotent request: a lost response may already have created a lease.
    state["allocation_pending"] = True
    write_state(path, state)
    try:
        result = client.request(
            "POST", "/v3/devices", {"billingType": "minutes", "filters": [filters]}
        )
    except ApiError as error:
        if 400 <= error.status < 500:
            state["allocation_pending"] = False
            write_state(path, state)
        raise
    state["devices"] = [
        {"serial": d["serial"], "slot": d["meta"]["slot"]["id"]}
        for d in result["devices"]
    ]
    state["allocation_pending"] = False
    write_state(path, state)
    if len(state["devices"]) != 1 or any(
        d["serial"] in state["baseline"] for d in state["devices"]
    ):
        raise RuntimeError(
            "Unexpected lease response; inspect journal before proceeding"
        )
    device = state["devices"][0]
    serial = urllib.parse.quote(device["serial"], safe="")
    wait_until_ready(client, serial)
    try:
        key_info = client.request(
            "POST",
            "/v3/keys/adb",
            {
                "publicKey": public_key,
                "title": "megaproxy-ui-" + str(int(state["created_at"])),
            },
        )
    except ApiError as error:
        if error.status != 409:
            raise
        # Documented 409: this fingerprint already exists. It is not ours to delete.
        print(
            "Using an existing Selectel registration of the primary ADB key", flush=True
        )
    else:
        state["fingerprint"] = key_info["fingerprint"]
        write_state(path, state)
    client.request(
        "POST", "/v3/users/devices", {"serial": device["serial"], "timeout": 1200000}
    )
    wait_until_ready(client, serial)
    remote = client.request("POST", "/v3/users/devices/" + serial + "/remote-connect")
    endpoint = remote.get("remoteConnectUrl", "") if isinstance(remote, dict) else ""
    if not isinstance(endpoint, str) or not re.fullmatch(
        r"[A-Za-z0-9.-]+:[0-9]+", endpoint
    ):
        raise RuntimeError("Selectel did not return a valid ADB endpoint")
    state["endpoint"] = endpoint
    write_state(path, state)
    connect_device(path, endpoint)
    print("Device connected; lease journal saved", flush=True)


def release(client, path):
    if not path.exists():
        return
    state = read_state(path)
    if state.get("project") != client.project:
        raise RuntimeError("Lease belongs to a different project")
    if state.get("released"):
        return
    errors = []
    if state.get("allocation_pending"):
        errors.append(
            "Allocation response was lost: inspect project for a new lease; do not blindly retry"
        )
    for device in state.get("devices", []):
        if device["serial"] in state.get("baseline", []):
            errors.append("Refusing to remove a pre-existing device")
            continue
        serial = urllib.parse.quote(device["serial"], safe="")
        # Slot identity prevents a stale recovery job from deleting a newer lease.
        try:
            current = client.request("GET", "/v3/devices/" + serial)
            if current["meta"]["slot"]["id"] != device["slot"]:
                continue
            for suffix in ["/remote-connect", ""]:
                try:
                    client.request("DELETE", "/v3/users/devices/" + serial + suffix)
                except (ApiError, urllib.error.URLError) as error:
                    # Session failure must not prevent ending the paid rental.
                    detail = (
                        f"HTTP {error.status}"
                        if isinstance(error, ApiError)
                        else type(error).__name__
                    )
                    print(
                        f"Session cleanup warning ({suffix or 'assignment'}): {detail}; continuing lease removal",
                        flush=True,
                    )
            client.request(
                "DELETE", "/v3/devices/" + serial, {"slotID": device["slot"]}
            )
        except ApiError as e:
            if e.status != 404:
                errors.append(str(e))
        except (KeyError, urllib.error.URLError) as e:
            errors.append(type(e).__name__ + " while releasing device")
    if state.get("fingerprint"):
        try:
            client.request(
                "DELETE",
                "/v3/keys/adb/" + urllib.parse.quote(state["fingerprint"], safe=""),
            )
        except ApiError as e:
            if e.status != 404:
                errors.append(str(e))
    try:
        adb(path, "kill-server", check=False)
    except (RuntimeError, subprocess.TimeoutExpired):
        pass
    shutil.rmtree(path.parent / "adb-home", ignore_errors=True)
    if errors:
        raise RuntimeError("; ".join(errors) + f". Recovery journal: {path}")
    state["released"] = True
    write_state(path, state)
    print(
        "Paid device lease released; any ADB key registration created by this run removed",
        flush=True,
    )


def instrumentation_report(output, target):
    """Translate Android's instrumentation protocol; adb exit=0 alone is not success."""
    suite = ET.Element("testsuite", name="MegaProxy UI")
    fields = {}
    last_key = None
    completed = 0
    failures = 0
    passed = 0
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: ") :].partition("=")
            fields[key] = value
            last_key = key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code <= 0 and fields.get("test"):
                case = ET.SubElement(
                    suite,
                    "testcase",
                    classname=fields.get("class", ""),
                    name=fields["test"],
                )
                completed += 1
                if code in (-3, -4):
                    ET.SubElement(case, "skipped")
                elif code == 0:
                    passed += 1
                else:
                    failures += 1
                    ET.SubElement(case, "failure").text = fields.get(
                        "stack", "Instrumentation failure"
                    )
            fields = {}
            last_key = None
        elif last_key == "stack" and not line.startswith("INSTRUMENTATION_"):
            fields["stack"] += "\n" + line
    success = (
        passed > 0
        and failures == 0
        and "INSTRUMENTATION_CODE: -1" in output
        and re.search(r"OK \(\d+ tests?\)", output) is not None
    )
    if not success and failures == 0:
        failures += 1
        completed += 1
        ET.SubElement(
            ET.SubElement(suite, "testcase", name="instrumentation_finished"), "failure"
        ).text = "No successful instrumentation completion; see instrumentation.txt"
    suite.set("tests", str(completed))
    suite.set("failures", str(failures))
    ET.ElementTree(suite).write(target, encoding="utf-8", xml_declaration=True)
    return success


def run_tests(path):
    state = read_state(path)
    if state.get("released"):
        raise RuntimeError("Device lease is already released")
    endpoint = state["endpoint"]
    reports = ROOT / "app/build/reports/selectel"
    result_id = os.environ.get("SELECTEL_RESULT_ID", "")
    if result_id:
        if not re.fullmatch(r"[a-z0-9-]+", result_id):
            raise RuntimeError("Invalid SELECTEL_RESULT_ID")
        reports = reports / result_id
    shutil.rmtree(reports, ignore_errors=True)
    reports.mkdir(parents=True, exist_ok=True)
    # Install only on the newly leased device, never a local emulator or personal phone.
    connect_device(path, endpoint)
    for apk in [
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]:
        print("Installing " + Path(apk).name, flush=True)
        adb(path, "-s", endpoint, "install", "-r", "-t", str(ROOT / apk), timeout=180)
    adb(path, "-s", endpoint, "shell", "pm", "clear", APP)
    print("Running instrumentation (maximum 10 minutes)", flush=True)
    try:
        output = adb(
            path,
            "-s",
            endpoint,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            RUNNER,
            timeout=600,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode(errors="replace")
        (reports / "instrumentation.txt").write_text(
            output + "\nInstrumentation timed out"
        )
        instrumentation_report(output, reports / "junit.xml")
        raise RuntimeError("Instrumentation exceeded 10 minutes") from None
    finally:
        try:
            adb(
                path,
                "-s",
                endpoint,
                "pull",
                "/sdcard/Android/data/" + APP + "/files/ui-test-screenshots",
                str(reports / "screenshots"),
                timeout=30,
                check=False,
            )
        except (RuntimeError, subprocess.TimeoutExpired):
            print(
                "Screenshot collection unavailable; instrumentation outcome is preserved",
                flush=True,
            )
    (reports / "instrumentation.txt").write_text(output)
    success = instrumentation_report(output, reports / "junit.xml")
    print(f'UI report: {reports / "junit.xml"}', flush=True)
    if not success:
        raise RuntimeError("UI instrumentation tests failed; see reports")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "action",
        choices=[
            "probe",
            "acquire",
            "run",
            "release",
            "release-all",
            "test",
            "matrix",
            "matrix-test",
        ],
    )
    parser.add_argument("--state", type=Path, default=ROOT / ".selectel/lease.json")
    parser.add_argument(
        "--profile", choices=["required", "additional"], default="required"
    )
    args = parser.parse_args()
    args.state = args.state.resolve()

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    try:
        if args.action == "matrix":
            print(json.dumps({"include": device_matrix(args.profile)}))
        elif args.action == "run":
            run_tests(args.state)
        else:
            client = Client()
            if args.action == "release-all":
                release_all(client, args.state.parent)
            elif args.action == "matrix-test":
                run_matrix(client, args.state, args.profile)
            elif args.action == "probe":
                existing = client.request("GET", "/v3/devices")
                model = choose_device(client.request("GET", "/v3/devices/available"))
                print(
                    f"Access OK; {len(existing)} existing devices untouched; candidate {model['marketName']} API {model['sdk']}"
                )
            elif args.action == "release":
                release(client, args.state)
            elif args.action == "acquire":
                acquire(client, args.state)
            else:
                try:
                    acquire(client, args.state)
                    run_tests(args.state)
                finally:
                    release(client, args.state)
    except KeyboardInterrupt:
        print(
            "Interrupted; run selectel_release if the lease remains active",
            file=sys.stderr,
        )
        return 130
    except Exception as e:
        # Avoid tracebacks containing request data or credentials.
        print(
            str(e) if isinstance(e, (RuntimeError, ApiError)) else type(e).__name__,
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
