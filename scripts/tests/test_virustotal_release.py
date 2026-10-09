import copy
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "virustotal_release", Path(__file__).parents[1] / "virustotal_release.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

PAYLOAD = b"signed APK bytes\x00\xff"
SHA256 = hashlib.sha256(PAYLOAD).hexdigest()
STATS = {"malicious": 0, "suspicious": 0, "undetected": 65, "harmless": 0}
COMPLETED = {
    "data": {"attributes": {"status": "completed", "stats": STATS}},
    "meta": {"file_info": {"sha256": SHA256}},
}


class VirusTotalReleaseTest(unittest.TestCase):
    def test_upload_streams_exact_bytes_and_waits_for_completed_matching_analysis(self):
        pending = {"data": {"attributes": {"status": "in-progress"}}}
        responses = iter(
            [
                {"data": "https://www.virustotal.com/_ah/upload/test"},
                {"data": {"id": "analysis/id=="}},
                pending,
                COMPLETED,
            ]
        )
        requests = []

        def respond(request, timeout):
            data = request.data.read() if request.data is not None else None
            requests.append((request, data))
            return io.BytesIO(json.dumps(next(responses)).encode())

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(m.urllib.request, "urlopen", side_effect=respond),
            patch.object(m.time, "sleep") as sleep,
            patch.object(m.time, "monotonic", return_value=100),
        ):
            apk = Path(directory) / "mega-proxy-universal.apk"
            apk.write_bytes(PAYLOAD)
            self.assertEqual(m.VirusTotal("test-key").scan(apk), (SHA256, STATS))
            request, body = requests[1]
            self.assertIn(PAYLOAD, body)
            self.assertIn(b'filename="mega-proxy-universal.apk"', body)
            self.assertEqual(int(request.get_header("Content-length")), len(body))
            self.assertEqual(request.get_method(), "POST")
            self.assertEqual(request.get_header("X-apikey"), "test-key")
            self.assertTrue(requests[-1][0].full_url.endswith("analysis%2Fid%3D%3D"))
            self.assertEqual(
                [call.args[0] for call in sleep.call_args_list], [0, 16, 16, 16]
            )

    def test_bad_hash_empty_verdicts_and_timeout_fail(self):
        bad_hash = copy.deepcopy(COMPLETED)
        bad_hash["meta"]["file_info"]["sha256"] = "0" * 64
        empty = copy.deepcopy(COMPLETED)
        empty["data"]["attributes"]["stats"] = {key: 0 for key in STATS}
        for result in (bad_hash, empty):
            with (
                self.subTest(result=result),
                tempfile.TemporaryDirectory() as directory,
                patch.object(
                    m.VirusTotal,
                    "request",
                    side_effect=[
                        {"data": "https://www.virustotal.com/_ah/upload/test"},
                        {"data": {"id": "analysis"}},
                        result,
                    ],
                ),
            ):
                apk = Path(directory) / "app.apk"
                apk.write_bytes(PAYLOAD)
                with self.assertRaises(RuntimeError):
                    m.VirusTotal("key").scan(apk)
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(
                m.VirusTotal,
                "request",
                side_effect=[{"data": "upload"}, {"data": {"id": "analysis"}}],
            ),
            patch.object(m.time, "monotonic", side_effect=[0, 601]),
        ):
            apk = Path(directory) / "app.apk"
            apk.write_bytes(PAYLOAD)
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                m.VirusTotal("key").scan(apk)

    def test_rejects_untrusted_upload_url_and_redacts_http_errors(self):
        with patch.object(m.urllib.request, "urlopen") as request:
            for url in ("http://www.virustotal.com/upload", "https://example.com"):
                with self.assertRaisesRegex(RuntimeError, "Unexpected"):
                    m.VirusTotal("secret").request(url)
            request.assert_not_called()
        for status in (401, 429, 500):
            with (
                self.subTest(status=status),
                patch.object(m.time, "sleep"),
                patch.object(
                    m.urllib.request,
                    "urlopen",
                    side_effect=urllib.error.HTTPError(
                        "secret-url", status, "secret-message", {}, None
                    ),
                ),
                self.assertRaisesRegex(RuntimeError, f"HTTP {status}$") as error,
            ):
                m.VirusTotal("secret-key").request(m.API + "/files")
            self.assertNotIn("secret", str(error.exception))

    def test_all_five_reports_survive_detections_and_reruns(self):
        for malicious, suspicious in ((0, 0), (1, 0), (0, 1)):
            with (
                self.subTest(malicious=malicious, suspicious=suspicious),
                tempfile.TemporaryDirectory() as directory,
                patch.dict(os.environ, {"VIRUSTOTAL_API_KEY": "key"}),
                patch.object(
                    m.VirusTotal,
                    "scan",
                    return_value=(
                        SHA256,
                        {**STATS, "malicious": malicious, "suspicious": suspicious},
                    ),
                ) as scan,
            ):
                root = Path(directory)
                for variant in m.VARIANTS:
                    (root / f"mega-proxy-{variant}.apk").write_bytes(PAYLOAD)
                for _ in range(2):
                    if malicious or suspicious:
                        with self.assertRaisesRegex(RuntimeError, "require review"):
                            m.scan_release(root)
                    else:
                        m.scan_release(root)
                    report = (root / "VIRUSTOTAL.md").read_text()
                    self.assertEqual(report.count("/detection)"), 5)
                    self.assertIn("Отчёты", report)
                self.assertEqual(scan.call_count, 10)

    def test_missing_key_or_apk_does_not_call_api(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(m.VirusTotal, "request") as request,
        ):
            with (
                patch.dict(os.environ, {}, clear=True),
                self.assertRaisesRegex(RuntimeError, "VIRUSTOTAL_API_KEY"),
            ):
                m.scan_release(Path(directory))
            with (
                patch.dict(os.environ, {"VIRUSTOTAL_API_KEY": "key"}),
                self.assertRaisesRegex(RuntimeError, "five"),
            ):
                m.scan_release(Path(directory))
            request.assert_not_called()

    def test_release_rerun_preserves_notes_and_replaces_report_block(self):
        workflow = (
            Path(__file__).parents[2] / ".github/workflows/release.yml"
        ).read_text()
        code = workflow.split("<<'PY'\n", 1)[1].split("\n          PY", 1)[0]
        code = "\n".join(line[10:] for line in code.splitlines())
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / "notes.md"
            report = Path(directory) / "report.md"
            notes.write_text("English\nРусский\n")
            report.write_text("new report\n")
            with patch.object(m.sys, "argv", ["-", str(notes), str(report)]):
                exec(code, {})
                first = notes.read_text()
                exec(code, {})
                self.assertEqual(notes.read_text(), first)
                notes.write_text(first + "\nManual addition\n")
                report.write_text("updated report\n")
                exec(code, {})
            body = notes.read_text()
            self.assertIn("English\nРусский", body)
            self.assertIn("Manual addition", body)
            self.assertNotIn("new report", body)
            self.assertEqual(body.count("updated report"), 1)

    def test_partial_report_and_summary_survive_api_failure(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(
                m.VirusTotal,
                "scan",
                side_effect=[(SHA256, STATS), RuntimeError("API unavailable")],
            ),
        ):
            root = Path(directory)
            summary = root / "summary.md"
            for variant in m.VARIANTS:
                (root / f"mega-proxy-{variant}.apk").write_bytes(PAYLOAD)
            with (
                patch.dict(
                    os.environ,
                    {"VIRUSTOTAL_API_KEY": "key", "GITHUB_STEP_SUMMARY": str(summary)},
                ),
                self.assertRaisesRegex(RuntimeError, "API unavailable"),
            ):
                m.scan_release(root)
            report = (root / "VIRUSTOTAL.md").read_text()
            self.assertEqual(report.count("/detection)"), 1)
            self.assertEqual(summary.read_text(), report)


if __name__ == "__main__":
    unittest.main()
