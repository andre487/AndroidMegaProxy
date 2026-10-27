import errno
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, call, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import android_device_tests as m


class InstrumentationResultTest(unittest.TestCase):
    def test_vpn_consent_denial_precedes_tests_that_grant_consent(self):
        self.assertEqual("VpnDeviceTest#deniedVpnConsentDoesNotStartTunnel", m.TESTS[0])

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
        # Strict source ownership uses Android API 29+ and is selected separately.
        selected.add("strictSourceValidatedTcpUdpStopAndRestart")
        self.assertEqual(methods, selected)
        self.assertEqual(len(m.TESTS), len(set(m.TESTS)))

    def test_dns_scenarios_are_registered(self):
        source = (
            m.ROOT / "app/src/androidTest/java/net/megaproxy487/DnsDeviceTest.kt"
        ).read_text()
        methods = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
        selected = {
            name.split("#", 1)[1]
            for name in m.TESTS
            if name.startswith("DnsDeviceTest#")
        }
        self.assertEqual(methods, selected)

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
                ready = "Active default network: 100\nNetworkAgentInfo{ network{100} nc{[ Transports: WIFI Capabilities: INTERNET&NOT_VPN&VALIDATED]}}"
                connections = iter(
                    ["Active default network: 99", ready.replace("100", "99"), ready]
                    if api >= 35
                    else [ready]
                )

                def response(*args):
                    if args == ("dumpsys", "connectivity"):
                        return next(connections)
                    if args == ("dumpsys", "wifi"):
                        return "Wi-Fi is disabled"
                    return ""

                shell = Mock(side_effect=response)
                m.prepare_emulator(shell, api)
                self.assertIsNone(
                    next(connections, None),
                    "The stale pre-reconnect network must not satisfy readiness",
                )
                shell.assert_any_call("svc", "data", "disable")
                shell.assert_any_call(
                    "settings", "put", "global", "captive_portal_mode", "0"
                )
                shell.assert_any_call("am", "force-stop", package)
                shell.assert_any_call("pm", "disable-user", "--user", "0", package)
                if api == 26:
                    self.assertNotIn(
                        call("svc", "wifi", "disable"), shell.call_args_list
                    )
                    self.assertNotIn(
                        call("svc", "wifi", "enable"), shell.call_args_list
                    )
                else:
                    shell.assert_any_call("svc", "wifi", "disable")
                    shell.assert_any_call("svc", "wifi", "enable")
                self.assertNotIn(m.PACKAGE, str(shell.call_args_list))

    def test_emulator_rejects_an_unvalidated_default_network(self):
        for state in (
            "Active default network: 100\nNetworkAgentInfo{ network{101} nc{[ Transports: WIFI Capabilities: INTERNET&VALIDATED]}}",
            "Active default network: 100\nNetworkAgentInfo{ network{100} Score(IS_VALIDATED) nc{[ Transports: WIFI Capabilities: INTERNET&NOT_VPN]}}",
        ):
            with (
                self.subTest(state=state),
                patch.object(m.time, "monotonic", side_effect=[0, 31]),
            ):
                with self.assertRaisesRegex(RuntimeError, "no validated default Wi-Fi"):
                    m.prepare_emulator(Mock(return_value=state), 26)

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
                m.finish_case(shell, results, "test", case, suite)
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

    def test_failed_case_collects_anr_stacks_before_stopping_app(self):
        suite = ET.Element("testsuite")
        case = ET.SubElement(suite, "testcase", time="1.0")
        ET.SubElement(case, "failure", message="Instrumentation timed out")
        shell = Mock(side_effect=["logcat", "ANR thread stacks", ""])
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            m.finish_case(shell, results, "test", case, suite)
            self.assertEqual(
                (results / "test-anr.txt").read_text(), "ANR thread stacks"
            )
        self.assertEqual(
            shell.call_args_list[-2:],
            [
                call("dumpsys", "dropbox", "--print", "data_app_anr"),
                call("am", "force-stop", m.PACKAGE),
            ],
        )
        self.assertEqual(suite.get("failures"), "1")

    def test_case_waits_for_previous_process_and_activity_before_clearing_logcat(self):
        responses = iter(
            [
                "321",
                "",
                "",
                "ActivityRecord{abc net.megaproxy487/.MainActivity}",
                "",
                "",
            ]
        )

        def response(*args):
            if args[0] in ("sh", "dumpsys"):
                return next(responses)
            return ""

        shell = Mock(side_effect=response)
        with patch.object(m.time, "sleep"):
            m.begin_case(shell, 26, "case")
        self.assertEqual(
            shell.call_args_list[-2:],
            [
                call("logcat", "-c"),
                call("log", "-t", "MegaProxyDeviceTest", "BEGIN case"),
            ],
        )
        self.assertEqual(
            shell.call_args_list.count(call("dumpsys", "activity", "activities")), 3
        )

    def test_previous_process_timeout_does_not_clear_evidence(self):
        shell = Mock(return_value="still running")
        with patch.object(m.time, "monotonic", side_effect=[0, 16]):
            with self.assertRaisesRegex(RuntimeError, "did not finish"):
                m.begin_case(shell, 35, "deniedNotifications")
        self.assertNotIn(call("logcat", "-c"), shell.call_args_list)
        shell.assert_any_call(
            "pm", "revoke", m.PACKAGE, "android.permission.POST_NOTIFICATIONS"
        )

    def test_dual_port_retries_only_address_collisions_and_closes_both_sockets(self):
        for error, attempts in [(errno.EADDRINUSE, 2), (errno.EACCES, 1)]:
            with self.subTest(error=error):
                tcp, udp = Mock(), Mock()
                tcp.getsockname.return_value = ("127.0.0.1", 12345)
                udp.bind.side_effect = [OSError(error, "bind"), None]
                sockets = [Mock(), Mock(), Mock(), Mock()]
                for index, sock in enumerate(sockets):
                    sock.__enter__ = Mock(return_value=tcp if index % 2 == 0 else udp)
                    sock.__exit__ = Mock(return_value=False)
                with patch.object(m.socket, "socket", side_effect=sockets):
                    if error == errno.EADDRINUSE:
                        self.assertEqual(m.dual_protocol_port(), 12345)
                    else:
                        with self.assertRaises(OSError):
                            m.dual_protocol_port()
                for sock in sockets[: attempts * 2]:
                    sock.__exit__.assert_called_once()

    def test_dual_port_collision_retries_are_bounded(self):
        tcp, udp = Mock(), Mock()
        tcp.getsockname.return_value = ("127.0.0.1", 12345)
        udp.bind.side_effect = OSError(errno.EADDRINUSE, "bind")
        sockets = [Mock(), Mock()] * 8
        for index, sock in enumerate(sockets[:2]):
            sock.__enter__ = Mock(return_value=tcp if index == 0 else udp)
            sock.__exit__ = Mock(return_value=False)
        with patch.object(m.socket, "socket", side_effect=sockets):
            with self.assertRaises(OSError):
                m.dual_protocol_port()
        self.assertEqual(udp.bind.call_count, 8)

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
