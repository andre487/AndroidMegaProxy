import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, call

sys.path.insert(0, str(Path(__file__).parents[1]))
import android_device_tests as m


class InstrumentationResultTest(unittest.TestCase):
    def test_vpn_scenarios_are_registered(self):
        source = (
            m.ROOT / "app/src/androidTest/java/net/megaproxy487/VpnDeviceTest.kt"
        ).read_text()
        methods = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
        selected = {
            name.split("#", 1)[1]
            for name in m.TESTS
            if name.startswith("VpnDeviceTest#")
        }
        # The runner selects this permission scenario separately on API >= 33.
        selected.add("deniedNotificationsStillAllowVpn")
        self.assertEqual(methods, selected)
        self.assertEqual(len(m.TESTS), len(set(m.TESTS)))

    def test_document_scenarios_are_registered(self):
        source = (
            m.ROOT / "app/src/androidTest/java/net/megaproxy487/DocumentsDeviceTest.kt"
        ).read_text()
        methods = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
        selected = {
            name.split("#", 1)[1]
            for name in m.TESTS
            if name.startswith("DocumentsDeviceTest#")
        }
        self.assertEqual(methods, selected)

    def test_disposable_emulator_removes_observed_background_dialog_sources(self):
        for api, package in [
            (26, "com.google.android.apps.messaging"),
            (35, "com.google.android.apps.nexuslauncher"),
        ]:
            with self.subTest(api=api):
                shell = Mock()
                m.prepare_emulator(shell, api)
                self.assertEqual(
                    [
                        call("svc", "data", "disable"),
                        call("am", "force-stop", package),
                        call("pm", "disable-user", "--user", "0", package),
                    ],
                    shell.call_args_list,
                )
                self.assertNotIn(m.PACKAGE, str(shell.call_args_list))

    def test_logcat_failure_preserves_evidence_and_failed_report(self):
        suite = ET.Element("testsuite")
        case = ET.SubElement(suite, "testcase", time="1.0")
        shell = Mock(
            side_effect=[
                subprocess.CalledProcessError(
                    255, "logcat", output="read: unexpected EOF!"
                ),
                subprocess.CalledProcessError(1, "force-stop", output="device offline"),
            ]
        )
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "logcat failed"):
                m.finish_case(shell, results, "test", case, suite, "10-07 10:58:32.000")
            report = ET.parse(results / "junit.xml").getroot()
            self.assertEqual(report.get("tests"), "1")
            self.assertEqual(report.get("failures"), "1")
            self.assertEqual(len(report[0].findall("failure")), 2)
            self.assertEqual(
                (results / "test-logcat.txt").read_text(), "read: unexpected EOF!"
            )
            self.assertEqual(
                (results / "test-force-stop.txt").read_text(), "device offline"
            )

    def test_adb_success_is_not_enough(self):
        for output in (
            "INSTRUMENTATION_CODE: 0",
            "INSTRUMENTATION_FAILED: process crashed",
            "FAILURES!!!\nTests run: 1, Failures: 1",
            "OK (0 tests)",
            "OK (1 test)\nFAILURES!!!",
        ):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                m.require_success(output)
        m.require_success("OK (1 test)\nINSTRUMENTATION_CODE: -1")


if __name__ == "__main__":
    unittest.main()
