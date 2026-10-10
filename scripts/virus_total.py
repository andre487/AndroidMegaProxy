"""Scan release APKs and attach hash-addressed VirusTotal reports to a release."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

API = "https://www.virustotal.com/api/v3/"
START = "<!-- megaproxy-virustotal -->"
END = "<!-- /megaproxy-virustotal -->"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError(
            "VirusTotal unexpectedly redirected an authenticated request"
        )


class Client:
    def __init__(self, key):
        if not re.fullmatch(r"[0-9a-fA-F]{64}", key):
            raise RuntimeError("VIRUSTOTAL_API_KEY is missing or invalid")
        self.key = key
        self.opener = urllib.request.build_opener(NoRedirect())
        self.next_request = 0.0
        self.deadline = time.monotonic() + 1800

    def request(self, url, body=None, content_type=None):
        parsed = urllib.parse.urlsplit(url)
        if (
            parsed.scheme != "https"
            or parsed.hostname not in ("www.virustotal.com", "bigfiles.virustotal.com")
            or parsed.port not in (None, 443)
            or parsed.username
            or parsed.password
        ):
            raise RuntimeError("VirusTotal returned an untrusted upload URL")
        delay = max(0, self.next_request - time.monotonic())
        if time.monotonic() + delay >= self.deadline:
            raise RuntimeError("VirusTotal scan deadline exceeded (30 minutes)")
        time.sleep(delay)
        self.next_request = time.monotonic() + 16  # Public API: at most 4/minute.
        headers = {"x-apikey": self.key}
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=body, headers=headers)
        try:
            with self.opener.open(
                req, timeout=min(120, self.deadline - time.monotonic())
            ) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"VirusTotal API failed (HTTP {error.code})") from None
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError("VirusTotal network request failed") from None

    def scan(self, path):
        content = path.read_bytes()
        if not 0 < len(content) <= 650 * 1024 * 1024:
            raise RuntimeError("APK size is outside VirusTotal upload limits")
        digest = hashlib.sha256(content).hexdigest()
        url = API + "files"
        if len(content) >= 32 * 1024 * 1024:
            url = self.request(API + "files/upload_url")["data"]
        boundary = uuid.uuid4().hex
        body = (
            (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{path.name}"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n'
            ).encode()
            + content
            + f"\r\n--{boundary}--\r\n".encode()
        )
        analysis_id = self.request(
            url, body, f"multipart/form-data; boundary={boundary}"
        )["data"]["id"]
        while True:
            response = self.request(
                API + "analyses/" + urllib.parse.quote(analysis_id, safe="")
            )
            attributes = response["data"]["attributes"]
            if attributes["status"] == "completed":
                break
            if attributes["status"] not in ("queued", "in-progress"):
                raise RuntimeError("VirusTotal returned an unknown analysis status")
        reported = response.get("meta", {}).get("file_info", {}).get("sha256")
        if reported is not None and reported != digest:
            raise RuntimeError("VirusTotal analysis hash differs from uploaded APK")
        stats = attributes["stats"]
        if (
            not stats
            or any(type(value) is not int or value < 0 for value in stats.values())
            or not all(
                key in stats for key in ("malicious", "suspicious", "undetected")
            )
            or sum(
                stats.get(k, 0)
                for k in ("malicious", "suspicious", "harmless", "undetected")
            )
            == 0
        ):
            raise RuntimeError("VirusTotal completed without usable engine verdicts")
        return {
            "file": path.name,
            "sha256": digest,
            "analysis_id": analysis_id,
            "stats": stats,
            "url": f"https://www.virustotal.com/gui/file/{digest}",
        }


def scan_artifacts(directory):
    paths = sorted(directory.glob("*.apk"))
    if not paths or any(not re.fullmatch(r"[\w.-]+\.apk", p.name) for p in paths):
        raise RuntimeError("No release APKs found or invalid APK filename")
    client = Client(os.environ.get("VIRUSTOTAL_API_KEY", "").strip())
    rows = []
    complete = False
    try:
        for path in paths:
            print(f"Scanning {path.name}", flush=True)
            rows.append(client.scan(path))
        complete = True
    finally:
        report = "## VirusTotal\n\n"
        if not complete:
            report += "**Scan incomplete; release publication blocked.**\n\n"
        report += (
            "| APK | Malicious | Suspicious | Report |\n| --- | --- | --- | --- |\n"
        )
        for row in rows:
            stats = row["stats"]
            report += f"| {row['file']} | {stats['malicious']} | {stats['suspicious']} | [SHA-256 report]({row['url']}) |\n"
        report += "\nCompleted engine verdicts are a point-in-time check, not a guarantee of safety. See each report for unsupported engines, failures and timeouts.\n"
        (directory / "VIRUSTOTAL.md").write_text(report)
        (directory / "VIRUSTOTAL.json").write_text(json.dumps(rows, indent=2) + "\n")
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(summary, "a") as output:
                output.write(report)
    if any(row["stats"]["malicious"] or row["stats"]["suspicious"] for row in rows):
        raise RuntimeError("VirusTotal detections require review; publication blocked")


def attach_report(directory, tag):
    # Native gh preserves authentication; release text stays in a file, never shell code.
    result = subprocess.run(
        ["gh", "release", "view", tag, "--json", "body"],
        check=True,
        capture_output=True,
        text=True,
    )
    body = json.loads(result.stdout)["body"]
    body = re.sub(
        re.escape(START) + ".*?" + re.escape(END), "", body, flags=re.S
    ).rstrip()
    notes = directory / "release-notes-virustotal.md"
    notes.write_text(
        body
        + "\n\n"
        + START
        + "\n"
        + (directory / "VIRUSTOTAL.md").read_text()
        + END
        + "\n"
    )
    subprocess.run(
        ["gh", "release", "edit", tag, "--notes-file", str(notes)], check=True
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, default=Path("dist/release"))
    parser.add_argument("--attach-to-release")
    args = parser.parse_args()
    try:
        if args.attach_to_release:
            attach_report(args.artifacts, args.attach_to_release)
        else:
            scan_artifacts(args.artifacts)
    except (
        RuntimeError,
        KeyError,
        TypeError,
        ValueError,
        OSError,
        subprocess.SubprocessError,
    ) as error:
        # Malformed JSON/API payloads must not print upload URLs or credentials.
        message = (
            str(error)
            if isinstance(error, RuntimeError)
            else "VirusTotal operation failed"
        )
        raise SystemExit(message) from None


if __name__ == "__main__":
    main()
