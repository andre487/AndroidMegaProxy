import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    "virus_total", Path(__file__).parents[1] / "virus_total.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class VirusTotalTests(unittest.TestCase):
    def test_upload_large_apk_waits_for_complete_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mega-proxy-universal.apk"
            path.write_bytes(b"A" * (32 * 1024 * 1024))
            client = m.Client("a" * 64)
            with patch.object(
                client,
                "request",
                side_effect=[
                    {"data": "https://bigfiles.virustotal.com/_ah/upload/test"},
                    {"data": {"id": "analysis/id"}},
                    {"data": {"attributes": {"status": "queued"}}},
                    {"data": {"attributes": {"status": "in-progress"}}},
                    {
                        "data": {
                            "attributes": {
                                "status": "completed",
                                "stats": {
                                    "malicious": 0,
                                    "suspicious": 0,
                                    "undetected": 60,
                                },
                            }
                        }
                    },
                ],
            ) as request:
                row = client.scan(path)
            self.assertEqual(5, request.call_count)
            self.assertEqual(
                m.API + "files/upload_url", request.call_args_list[0].args[0]
            )
            upload = request.call_args_list[1].args
            self.assertEqual(
                "https://bigfiles.virustotal.com/_ah/upload/test", upload[0]
            )
            self.assertIn(b'filename="mega-proxy-universal.apk"', upload[1])
            self.assertIn(b"A" * 1000, upload[1])
            self.assertIn("analysis%2Fid", request.call_args_list[-1].args[0])
            self.assertTrue(row["url"].endswith(row["sha256"]))

    def test_untrusted_upload_url_and_redirect_cannot_receive_key(self):
        client = m.Client("a" * 64)
        client.opener = Mock()
        for url in [
            "http://www.virustotal.com/upload",
            "https://evil.test/upload",
            "https://www.virustotal.com.evil.test/upload",
            "https://user@www.virustotal.com/upload",
            "https://www.virustotal.com:444/upload",
        ]:
            with self.subTest(url=url), self.assertRaises(RuntimeError):
                client.request(url)
        client.opener.open.assert_not_called()
        with self.assertRaises(RuntimeError):
            m.NoRedirect().redirect_request(
                None, None, 302, "", {}, "https://evil.test"
            )

    def test_api_quota_deadline_and_error_do_not_expose_key(self):
        client = m.Client("a" * 64)
        client.opener = Mock()
        client.next_request = 110
        client.deadline = 1000
        error = m.urllib.error.HTTPError(m.API, 429, "secret", {}, None)
        client.opener.open.side_effect = error
        with (
            patch.object(m.time, "monotonic", return_value=100),
            patch.object(m.time, "sleep") as sleep,
        ):
            with self.assertRaisesRegex(
                RuntimeError, r"^VirusTotal API failed \(HTTP 429\)$"
            ):
                client.request(m.API + "files/upload_url")
            sleep.assert_called_once_with(10)
        client.deadline = 99
        with (
            patch.object(m.time, "monotonic", return_value=100),
            self.assertRaisesRegex(RuntimeError, "deadline"),
        ):
            client.request(m.API)

    def test_detections_and_incomplete_scans_block_with_partial_reports(self):
        row = {
            "file": "test.apk",
            "sha256": "a" * 64,
            "url": "https://www.virustotal.com/gui/file/" + "a" * 64,
            "stats": {"malicious": 1, "suspicious": 0, "undetected": 60},
        }
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(os.environ, {"VIRUSTOTAL_API_KEY": "a" * 64}),
        ):
            directory = Path(tmp)
            (directory / "test.apk").write_bytes(b"APK")
            with (
                patch.object(m.Client, "scan", return_value=row),
                self.assertRaisesRegex(RuntimeError, "detections"),
            ):
                m.scan_artifacts(directory)
            self.assertIn("| 1 | 0 |", (directory / "VIRUSTOTAL.md").read_text())
            (directory / "z.apk").write_bytes(b"APK")
            with (
                patch.object(
                    m.Client, "scan", side_effect=[row, RuntimeError("timeout")]
                ),
                self.assertRaisesRegex(RuntimeError, "timeout"),
            ):
                m.scan_artifacts(directory)
            self.assertIn("Scan incomplete", (directory / "VIRUSTOTAL.md").read_text())
            self.assertEqual(
                [row], json.loads((directory / "VIRUSTOTAL.json").read_text())
            )

    def test_release_rerun_preserves_notes_without_duplicate_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "VIRUSTOTAL.md").write_text("## VirusTotal\nnew report\n")
            old = f"Original EN/RU notes\n\n{m.START}\nold report\n{m.END}\n\nUser addition"
            result = Mock(stdout=json.dumps({"body": old}))
            with patch.object(m.subprocess, "run", return_value=result) as run:
                m.attach_report(directory, "v1.0.4")
            body = (directory / "release-notes-virustotal.md").read_text()
            self.assertIn("Original EN/RU notes", body)
            self.assertIn("User addition", body)
            self.assertNotIn("old report", body)
            self.assertEqual(1, body.count(m.START))
            self.assertIn("--notes-file", run.call_args.args[0])
