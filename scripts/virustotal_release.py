#!/usr/bin/env python3
"""Scan signed release APKs and write a bilingual VirusTotal report."""

import hashlib
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit

API = "https://www.virustotal.com/api/v3"
VARIANTS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class VirusTotal:
    def __init__(self, key):
        require(key, "Configure the VIRUSTOTAL_API_KEY repository secret")
        self.key = key
        self.next_request = 0

    def request(self, url, data=None, headers=None):
        parsed = urlsplit(url)
        require(
            parsed.scheme == "https" and parsed.netloc == "www.virustotal.com",
            "Unexpected VirusTotal upload URL",
        )
        time.sleep(max(0, self.next_request - time.monotonic()))
        self.next_request = time.monotonic() + 16  # Public API: four requests/minute.
        request = urllib.request.Request(
            url, data=data, headers={"x-apikey": self.key, **(headers or {})}
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"VirusTotal API returned HTTP {error.code}") from None
        except urllib.error.URLError:
            raise RuntimeError("VirusTotal API connection failed") from None

    def scan(self, apk):
        require(0 < apk.stat().st_size <= 650 * 1024 * 1024, "Invalid APK size")
        upload_url = self.request(f"{API}/files/upload_url")["data"]
        boundary = uuid.uuid4().hex
        digest = hashlib.sha256()
        # A temporary file lets urllib stream large APKs without buffering in RAM.
        with tempfile.TemporaryFile() as body, apk.open("rb") as source:
            body.write(
                (
                    f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                    f'filename="{apk.name}"\r\n'
                    "Content-Type: application/vnd.android.package-archive\r\n\r\n"
                ).encode()
            )
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
                body.write(chunk)
            body.write(f"\r\n--{boundary}--\r\n".encode())
            length = body.tell()
            body.seek(0)
            analysis_id = self.request(
                upload_url,
                body,
                {
                    "Content-Type": f"multipart/form-data; boundary={boundary}",
                    "Content-Length": str(length),
                },
            )["data"]["id"]
        deadline = time.monotonic() + 10 * 60
        while time.monotonic() < deadline:
            analysis = self.request(f"{API}/analyses/{quote(analysis_id, safe='')}")
            attributes = analysis["data"]["attributes"]
            if attributes["status"] == "completed":
                require(
                    analysis["meta"]["file_info"]["sha256"] == digest.hexdigest(),
                    "VirusTotal report does not match the uploaded APK",
                )
                stats = attributes["stats"]
                require(
                    all(type(value) is int and value >= 0 for value in stats.values())
                    and all(
                        key in stats
                        for key in ("malicious", "suspicious", "undetected", "harmless")
                    )
                    and stats["undetected"]
                    + stats["harmless"]
                    + stats["malicious"]
                    + stats["suspicious"]
                    > 0,
                    "VirusTotal returned no usable engine verdicts",
                )
                return digest.hexdigest(), stats
            require(
                attributes["status"] in ("queued", "in-progress"),
                "Unexpected VirusTotal analysis status",
            )
        raise RuntimeError(f"VirusTotal analysis timed out for {apk.name}")


def scan_release(directory):
    client = VirusTotal(os.environ.get("VIRUSTOTAL_API_KEY"))
    apks = [directory / f"mega-proxy-{variant}.apk" for variant in VARIANTS]
    require(
        set(directory.glob("*.apk")) == set(apks),
        "Expected exactly the five signed release APKs",
    )
    report = directory / "VIRUSTOTAL.md"
    lines = [
        "## VirusTotal\n",
        "Reports for the exact signed APKs; no detections is not a safety guarantee.\n",
        "Отчёты для подписанных APK; отсутствие обнаружений не гарантирует безопасность.\n",
        "| APK | Report / Отчёт | Malicious / Вредоносные | Suspicious / Подозрительные |",
        "| --- | --- | --- | --- |",
    ]
    detections = False
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    try:
        for apk in apks:
            print(f"Scanning {apk.name}", flush=True)
            sha256, stats = client.scan(apk)
            url = f"https://www.virustotal.com/gui/file/{sha256}/detection"
            lines.append(
                f"| {apk.name} | [SHA-256: {sha256}]({url}) | "
                f"{stats['malicious']} | {stats['suspicious']} |"
            )
            report.write_text("\n".join(lines) + "\n", encoding="utf-8")
            detections |= stats["malicious"] > 0 or stats["suspicious"] > 0
    finally:
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as output:
                output.write(report.read_text(encoding="utf-8"))
    require(not detections, "VirusTotal detections require review before publication")


if __name__ == "__main__":
    try:
        scan_release(Path(os.environ.get("MEGAPROXY_RELEASE_DIR", "dist/release")))
    except (RuntimeError, KeyError, ValueError, OSError) as error:
        print(f"VirusTotal release check failed: {error}", file=sys.stderr)
        sys.exit(1)
