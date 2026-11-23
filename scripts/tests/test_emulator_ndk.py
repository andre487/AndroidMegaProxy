import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


class EmulatorNdkTest(unittest.TestCase):
    def test_install_once_and_respect_explicit_ndk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            scripts = root / "scripts"
            scripts.mkdir()
            run = scripts / "run-android-app.sh"
            shutil.copy(pathlib.Path(__file__).resolve().parents[1] / run.name, run)
            start = scripts / "start-android-emulator.sh"
            start.write_text('#!/bin/sh\necho "$ANDROID_NDK_HOME"\nexit 42\n')
            start.chmod(0o755)

            sdk = root / "Android SDK"
            manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text(
                '#!/bin/sh\nset -eu\ntest "$1" = "--sdk_root=$ANDROID_HOME"\n'
                'test "$2" = "ndk;29.0.14206865"\nmkdir -p "$ANDROID_NDK_HOME/meta"\n'
                'touch "$ANDROID_NDK_HOME/meta/platforms.json"\n'
            )
            manager.chmod(0o755)
            env = dict(os.environ, HOME=tmp, ANDROID_HOME=str(sdk), ANDROID_NDK_HOME="")

            def launch():
                return subprocess.run([run], env=env, capture_output=True, text=True)

            installed = sdk / "ndk/29.0.14206865"
            self.assertEqual(launch().stdout.strip(), str(installed))
            manager.unlink()
            self.assertEqual(launch().stdout.strip(), str(installed))
            custom = root / "external NDK"
            installed.rename(custom)
            env["ANDROID_NDK_HOME"] = str(custom)
            self.assertEqual(launch().stdout.strip(), str(custom))
            env["ANDROID_NDK_HOME"] = str(installed)
            result = launch()
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("platforms.json is missing", result.stderr)
