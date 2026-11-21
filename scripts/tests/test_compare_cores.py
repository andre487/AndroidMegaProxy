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
                engine="megaproxy",
                scenario="tcp_forward",
                streams=1,
                sample=index,
                receiver_mbps=value,
                core_cpu_percent=50,
                core_peak_rss_bytes=1048576,
            )
            for index, value in enumerate((1, 2, 99), 1)
        ]
        rows.append(
            dict(
                engine="direct",
                scenario="tcp_forward",
                streams=1,
                sample=1,
                receiver_mbps=100,
            )
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            m.summary(rows, root)
            text = (root / "summary.md").read_text()
            self.assertIn("| megaproxy | tcp_forward | 1 | 2.000 |", text)
            self.assertIn("| direct | tcp_forward | 1 | 100.000 |", text)
            self.assertEqual(5, len((root / "samples.csv").read_text().splitlines()))
