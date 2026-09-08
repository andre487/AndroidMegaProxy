import copy
import importlib.util
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    "github_actions", Path(__file__).parents[1] / "github_actions.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.client = m.GitHub(m.REPOSITORY)
        self.pr = {
            "number": 31,
            "state": "OPEN",
            "headRefOid": "abc",
            "headRefName": "feature/test",
            "isCrossRepository": False,
        }

        self.discovery = patch.object(
            self.client,
            "read_json",
            return_value=[
                {
                    "databaseId": 20,
                    "headSha": "abc",
                    "headBranch": "feature/test",
                    "status": "completed",
                    "conclusion": "failure",
                    "url": "https://github.com/run",
                }
            ],
        ).start()
        self.addCleanup(patch.stopall)

    def test_all_and_failed_use_distinct_rerun_arguments(self):
        self.assertEqual(
            ["run", "rerun", "20"], m.plan_run(self.client, self.pr, "ci")[0]
        )
        self.assertEqual(
            ["run", "rerun", "20", "--failed"],
            m.plan_run(self.client, self.pr, "failed")[0],
        )

    def test_failed_mode_rejects_successful_run(self):
        self.discovery.return_value[0]["conclusion"] = "success"
        with self.assertRaisesRegex(RuntimeError, "no failed conclusion"):
            m.plan_run(self.client, self.pr, "failed")

    def test_full_rerun_accepts_successful_run_with_skipped_checks(self):
        self.discovery.return_value[0]["conclusion"] = "success"
        self.assertEqual(
            ["run", "rerun", "20"], m.plan_run(self.client, self.pr, "ci")[0]
        )

    def test_dry_run_never_launches_or_requests_confirmation(self):
        with (
            patch.object(self.client, "run", side_effect=AssertionError),
            patch("builtins.input", side_effect=AssertionError),
        ):
            m.launch(self.client, self.pr, "failed", dry_run=True)

    def test_changed_or_closed_pr_prevents_dispatch(self):
        for change in ("sha", "closed", "fork"):
            current = copy.deepcopy(self.pr)
            if change == "sha":
                current["headRefOid"] = "new"
            elif change == "closed":
                current["state"] = "CLOSED"
            else:
                current["isCrossRepository"] = True
            with (
                patch.object(self.client, "pr", return_value=current),
                patch.object(self.client, "run", side_effect=AssertionError),
                patch("builtins.input", return_value="y"),
                self.assertRaisesRegex(RuntimeError, "PR changed"),
            ):
                m.launch(self.client, self.pr, "ci")

    def test_cancel_never_launches(self):
        with (
            patch.object(self.client, "run", side_effect=AssertionError),
            patch("builtins.input", return_value="n"),
        ):
            m.launch(self.client, self.pr, "ci")

    def test_confirmed_dispatch_launches_exactly_once(self):
        with (
            patch.object(self.client, "pr", return_value=self.pr),
            patch.object(self.client, "run", return_value="") as run,
            patch("builtins.input", return_value="y"),
        ):
            m.launch(self.client, self.pr, "ci")
        run.assert_called_once_with("run", "rerun", "20")

    def test_yes_skips_confirmation_but_still_rechecks_the_commit(self):
        with (
            patch.object(self.client, "pr", return_value=self.pr) as pr,
            patch.object(self.client, "run", return_value="") as run,
            patch("builtins.input", side_effect=AssertionError),
        ):
            m.launch(self.client, self.pr, "ci", yes=True)
        pr.assert_called_once_with(31)
        run.assert_called_once()
        changed = dict(self.pr, headRefOid="new")
        with (
            patch.object(self.client, "pr", return_value=changed),
            patch.object(self.client, "run", side_effect=AssertionError),
            patch("builtins.input", side_effect=AssertionError),
            self.assertRaisesRegex(RuntimeError, "PR changed"),
        ):
            m.launch(self.client, self.pr, "ci", yes=True)

    def test_dry_run_wins_over_yes(self):
        with (
            patch.object(self.client, "run", side_effect=AssertionError),
            patch("builtins.input", side_effect=AssertionError),
        ):
            m.launch(self.client, self.pr, "failed", dry_run=True, yes=True)

    def test_fork_is_rejected(self):
        self.pr["isCrossRepository"] = True
        with self.assertRaisesRegex(RuntimeError, "forks"):
            m.plan_run(self.client, self.pr, "failed")

    def test_ci_filters_current_commit_and_branch(self):
        run = {
            "databaseId": 20,
            "headSha": "abc",
            "headBranch": "feature/test",
            "status": "completed",
            "url": "https://github.com/run",
        }
        with patch.object(self.client, "read_json", return_value=[run]) as read:
            self.assertEqual(
                ["run", "rerun", "20"], m.plan_run(self.client, self.pr, "ci")[0]
            )
            args = read.call_args.args
            self.assertEqual("abc", args[args.index("--commit") + 1])
            self.assertEqual("feature/test", args[args.index("--branch") + 1])
            run["headSha"] = "old"
            with self.assertRaisesRegex(RuntimeError, "No CI run"):
                m.plan_run(self.client, self.pr, "ci")
            run.update(headSha="abc", status="in_progress")
            with self.assertRaisesRegex(RuntimeError, "already queued or running"):
                m.plan_run(self.client, self.pr, "ci")

    def test_arguments_are_passed_without_a_shell(self):
        with patch.object(
            m.subprocess, "run", return_value=Mock(returncode=0, stdout="[]", stderr="")
        ) as run:
            self.client.run("pr", "view", "branch/$(not-a-command)", "--json", "number")
        args, kwargs = run.call_args
        self.assertEqual(
            [
                "gh",
                "pr",
                "view",
                "branch/$(not-a-command)",
                "--json",
                "number",
                "--repo",
                m.REPOSITORY,
            ],
            args[0],
        )
        self.assertFalse(kwargs.get("shell", False))

    def test_timeout_never_retries_a_launch(self):
        with patch.object(
            m.subprocess, "run", side_effect=subprocess.TimeoutExpired(["gh"], 60)
        ) as run:
            with self.assertRaisesRegex(RuntimeError, "Check Actions before retrying"):
                self.client.run("run", "rerun", "20")
        self.assertEqual(1, run.call_count)

    def test_missing_cli_is_explained(self):
        with (
            patch.object(m.subprocess, "run", side_effect=FileNotFoundError),
            self.assertRaisesRegex(RuntimeError, "gh auth login"),
        ):
            self.client.run("pr", "list")

    def test_tokens_are_not_echoed_from_cli_errors(self):
        with (
            patch.dict(os.environ, {"GH_TOKEN": "test-token", "GH_DEBUG": "api"}),
            patch.object(
                m.subprocess,
                "run",
                return_value=Mock(returncode=1, stderr="failed test-token"),
            ) as run,
        ):
            with self.assertRaises(RuntimeError) as raised:
                self.client.run("pr", "list")
        self.assertNotIn("test-token", str(raised.exception))
        self.assertNotIn("GH_DEBUG", run.call_args.kwargs["env"])


if __name__ == "__main__":
    unittest.main()
