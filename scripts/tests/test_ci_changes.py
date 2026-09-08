import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

spec = importlib.util.spec_from_file_location(
    "ci_changes", Path(__file__).parents[1] / "ci_changes.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ChangeScopeTest(unittest.TestCase):
    def test_python_and_markdown_do_not_run_android(self):
        self.assertEqual(
            {"android": False, "native": False, "python": True},
            m.classify(["scripts/github_actions.py", "docs/ru/fastlane.md"]),
        )

    def test_docs_only_skip_all_suites(self):
        self.assertFalse(
            any(
                m.classify(
                    ["README.md", "native/README.md", "docs/assets/preview.png"]
                ).values()
            )
        )

    def test_runtime_markdown_is_android_content(self):
        self.assertTrue(m.classify(["app/src/main/assets/help.md"])["android"])

    def test_native_production_changes_rebuild_android(self):
        self.assertEqual(
            {"android": True, "native": True, "python": False},
            m.classify(["native/mobile/dialer.go"]),
        )
        self.assertEqual(
            {"android": False, "native": True, "python": False},
            m.classify(["native/mobile/dialer_test.go"]),
        )

    def test_build_inputs_and_unknown_paths_are_not_silently_skipped(self):
        for name in [
            "app/build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "scripts/build-fdroid-native.sh",
        ]:
            self.assertTrue(m.classify([name])["android"])
        for name in [
            ".github/workflows/ci.yml",
            "fastlane/Fastfile",
            "new-build-input.conf",
            "scripts/ci_changes.py",
            "scripts/ci_history.py",
        ]:
            self.assertTrue(all(m.classify([name]).values()))

    def test_first_push_runs_everything_and_diff_failure_is_not_empty_diff(self):
        self.assertIsNone(m.changed_files("0" * 40, "a" * 40, False))
        with (
            patch.object(
                m.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git")
            ),
            self.assertRaises(RuntimeError),
        ):
            m.changed_files("a" * 40, "b" * 40)

    def test_history_failure_falls_back_to_full_diff(self):
        output = io.StringIO()
        with (
            patch.object(
                sys,
                "argv",
                ["ci_changes.py", "--base", "a" * 40, "--head", "b" * 40, "--history"],
            ),
            patch.dict(
                os.environ,
                {
                    "GITHUB_REPOSITORY": "owner/repo",
                    "PR_BRANCH": "feature",
                    "PR_NUMBER": "32",
                    "GITHUB_RUN_ID": "40",
                },
            ),
            patch.object(
                m, "successful_baselines", side_effect=RuntimeError("unavailable")
            ),
            patch.object(
                m, "changed_files", return_value=["app/src/main/Main.kt"]
            ) as diff,
            patch.object(sys, "stdout", output),
        ):
            self.assertEqual(0, m.main())
        self.assertTrue(json.loads(output.getvalue())["android"])
        diff.assert_called_once_with("a" * 40, "b" * 40, True)

    def test_full_pr_diff_includes_earlier_commits_and_deleted_code(self):
        with tempfile.TemporaryDirectory() as root:

            def git(*args):
                return subprocess.check_output(
                    ["git", "-C", root, *args], text=True
                ).strip()

            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            git("config", "commit.gpgsign", "false")
            path = Path(root) / "README.md"
            path.write_text("base")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            code = Path(root) / "app/src/main/example.kt"
            code.parent.mkdir(parents=True)
            code.write_text("code")
            git("add", ".")
            git("commit", "-qm", "Android change")
            checked = git("rev-parse", "HEAD")
            path.write_text("docs only in latest commit")
            git("add", ".")
            git("commit", "-qm", "docs")
            head = git("rev-parse", "HEAD")
            original = m.subprocess.run
            with patch.object(
                m.subprocess,
                "run",
                side_effect=lambda *a, **kw: original(*a, cwd=root, **kw),
            ):
                self.assertTrue(m.classify(m.changed_files(base, head))["android"])
                result, detail = m.select_suites(
                    base, head, baselines={"android": {"sha": checked, "run_id": 1}}
                )
                self.assertFalse(result["android"])
                self.assertEqual(1, detail["android"]["run_id"])
            previous = head
            code.rename(Path(root) / "moved.md")
            git("add", "-A")
            git("commit", "-qm", "move code to docs")
            head = git("rev-parse", "HEAD")
            with patch.object(
                m.subprocess,
                "run",
                side_effect=lambda *a, **kw: original(*a, cwd=root, **kw),
            ):
                files = m.changed_files(previous, head)
                self.assertIn("app/src/main/example.kt", files)
                self.assertTrue(m.classify(files)["android"])


if __name__ == "__main__":
    unittest.main()
