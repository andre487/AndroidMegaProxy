import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "emulator-abi.sh"


class EmulatorAbiTest(unittest.TestCase):
    def test_host_machine_selects_the_android_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            uname = pathlib.Path(tmp) / "uname"
            uname.write_text('#!/bin/sh\necho "$TEST_MACHINE"\n')
            uname.chmod(0o755)
            for machine, abi in (
                ("x86_64", "x86_64"),
                ("amd64", "x86_64"),
                ("arm64", "arm64-v8a"),
                ("aarch64", "arm64-v8a"),
                ("riscv64", None),
            ):
                with self.subTest(machine=machine):
                    result = subprocess.run(
                        [SCRIPT],
                        env=dict(
                            os.environ,
                            PATH=tmp + ":" + os.environ["PATH"],
                            TEST_MACHINE=machine,
                        ),
                        capture_output=True,
                        text=True,
                    )
                    if abi is None:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(machine, result.stderr)
                    else:
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertEqual(result.stdout.strip(), abi)
