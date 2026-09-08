"""Exercise JDK validation without building or accessing signing material."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "java-toolchain.sh"


class JavaToolchainTests(unittest.TestCase):
    def run_toolchain(self, version, compiler=True):
        with tempfile.TemporaryDirectory(prefix="megaproxy jdk ") as directory:
            jdk = Path(directory)
            (jdk / "bin").mkdir()
            java = jdk / "bin/java"
            java.write_text(
                "#!/bin/sh\n" f"echo '    java.specification.version = {version}' >&2\n"
            )
            java.chmod(0o755)
            if compiler:
                javac = jdk / "bin/javac"
                javac.write_text("#!/bin/sh\nexit 0\n")
                javac.chmod(0o755)
            env = dict(os.environ, JAVA_HOME=str(jdk))
            return subprocess.run(
                [
                    "bash",
                    "-c",
                    'set -e; source "$1"; test -x "$JAVA_HOME/bin/javac"',
                    "bash",
                    str(SCRIPT),
                ],
                env=env,
                capture_output=True,
                text=True,
                timeout=10,
            )

    def test_accepts_jdk21_in_path_with_spaces(self):
        result = self.run_toolchain(21)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_wrong_version(self):
        self.assertNotEqual(0, self.run_toolchain(17).returncode)

    def test_rejects_runtime_without_compiler(self):
        self.assertNotEqual(0, self.run_toolchain(21, compiler=False).returncode)
