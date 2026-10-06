import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
import android_device_tests as m


class InstrumentationResultTest(unittest.TestCase):
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
