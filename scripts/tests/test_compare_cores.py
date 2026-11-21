import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import compare_cores as m


class CoreComparisonTest(unittest.TestCase):
    def test_iperf_uses_receiver_goodput_and_preserves_loss(self):
        result = m.iperf_metrics(
            {
                "end": {
                    "sum_received": {"bits_per_second": 2_000_000},
                    "sum_sent": {"bits_per_second": 8_000_000, "retransmits": 4},
                }
            },
            False,
        )
        self.assertEqual({"receiver_mbps": 2, "tcp_retransmits": 4}, result)
        result = m.iperf_metrics(
            {
                "end": {
                    "sum": {"bits_per_second": 9_000_000},
                    "sum_received": {
                        "bits_per_second": 1_000_000,
                        "lost_percent": 50,
                        "jitter_ms": 2,
                    },
                }
            },
            True,
        )
        self.assertEqual(
            {"receiver_mbps": 1, "udp_loss_percent": 50, "udp_jitter_ms": 2}, result
        )
        with self.assertRaisesRegex(RuntimeError, "iperf3 failed"):
            m.iperf_metrics({"error": "connection failed"}, False)

    def test_host_network_is_rejected_before_creating_anything(self):
        with (
            patch.object(m.os, "geteuid", return_value=0),
            patch.object(m.os, "readlink", return_value="net:[123]"),
        ):
            with self.assertRaisesRegex(RuntimeError, "private network namespace"):
                m.run(SimpleNamespace())

    def test_summary_keeps_engines_and_scenarios_separate(self):
        rows = [
            dict(
                transport="socks5",
                engine="megaproxy",
                scenario="tcp_forward",
                streams=1,
                sample=index,
                status="passed",
                receiver_mbps=value,
                core_cpu_percent=50,
                core_peak_rss_bytes=1048576,
            )
            for index, value in enumerate((1, 2, 99), 1)
        ]
        rows.append(
            dict(
                transport="direct",
                engine="direct",
                scenario="tcp_forward",
                streams=1,
                sample=1,
                status="passed",
                receiver_mbps=100,
            )
        )
        rows.append(
            dict(
                transport="https-h1",
                engine="megaproxy",
                scenario="tcp_forward",
                streams=1,
                sample=1,
                status="passed",
                receiver_mbps=900,
            )
        )
        rows.append(
            dict(
                transport="socks5",
                engine="megaproxy",
                scenario="tcp_forward",
                streams=1,
                sample=4,
                status="failed",
                error="control socket closed",
            )
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            m.summary(rows, root)
            text = (root / "summary.md").read_text()
            self.assertIn(
                "| socks5 | megaproxy | tcp_forward | 1 | 3/4 | 2.000 |", text
            )
            self.assertIn("| direct | direct | tcp_forward | 1 | 1/1 | 100.000 |", text)
            self.assertEqual(7, len((root / "samples.csv").read_text().splitlines()))
            self.assertIn("Failed measurements", text)
            self.assertIn("control socket closed", text)

    def test_transport_matrix_never_substitutes_protocols_or_direct_udp(self):
        material = dict(
            password="temporary-fixture",
            private_key="temporary-key",
            key_path="/private/client",
            entry_fingerprint="SHA256:entry",
            exit_fingerprint="SHA256:exit",
            entry_public_key="entry-key",
            exit_public_key="exit-key",
        )
        for name, support in m.TRANSPORTS.items():
            with self.subTest(name=name):
                native, outbounds = m.transport_configs(name, material)
                self.assertFalse(native["bypassLocalNetworks"])
                self.assertEqual(support["sing_box"], outbounds is not None)
                if "jump" in name:
                    self.assertEqual("127.0.0.1", native["host"])
                    self.assertEqual(m.PROXY, native["jumpHost"])
                    if outbounds:
                        self.assertEqual("jump", outbounds[0]["detour"])
                        self.assertNotIn("detour", outbounds[1])
                if name.startswith("https"):
                    self.assertEqual(name.endswith("3"), native["preferHttp3"])
                    if outbounds:
                        version = int(name[-1])
                        self.assertEqual(
                            [{"1": "http/1.1", "2": "h2", "3": "h3"}[name[-1]]],
                            outbounds[0]["tls"]["alpn"],
                        )
                        if support.get("preview"):
                            for outbound in outbounds:
                                self.assertEqual(version, outbound["version"])
                                self.assertTrue(outbound["disable_version_fallback"])
                if name == "masque":
                    self.assertEqual(3, outbounds[0]["version"])
                    self.assertTrue(outbounds[0]["disable_version_fallback"])
                if support["udp"]:
                    self.assertTrue(name in ("socks5", "masque") or name.endswith("h3"))
                else:
                    self.assertTrue(
                        native["type"] in ("HTTPS", "HTTPS_JUMP", "SSH", "SSH_JUMP")
                    )

    def test_failed_measurement_retains_error_without_retry(self):
        result = SimpleNamespace(
            stdout='{"error":"control socket closed"}', stderr="", returncode=1
        )
        with patch.object(m, "command", return_value=result) as run:
            raw, resources = m.measure(["iperf3"], None)
        self.assertEqual("control socket closed", raw["error"])
        self.assertIsNone(resources["core_cpu_percent"])
        run.assert_called_once()
        with patch.object(
            m, "command", side_effect=m.subprocess.TimeoutExpired("iperf3", 60)
        ) as run:
            raw, _ = m.measure(["iperf3"], None)
        self.assertIn("timed out", raw["error"])
        run.assert_called_once()
