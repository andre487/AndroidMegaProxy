import copy
import importlib.util
import json
import os
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch

spec = importlib.util.spec_from_file_location(
    "github_actions", Path(__file__).parents[1] / "github_actions.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.client = Mock(repository=m.REPOSITORY)
        self.pr = {
            "number": 31,
            "state": "open",
            "head": {
                "sha": "abc",
                "ref": "feature/test",
                "repo": {"full_name": m.REPOSITORY},
            },
        }

    def test_dispatch_uses_selected_branch_and_profile(self):
        for mode in ("single", "all"):
            path, body, _ = m.plan_run(self.client, self.pr, mode)
            self.assertEqual("/actions/workflows/selectel-ui.yml/dispatches", path)
            self.assertEqual({"ref": "feature/test", "inputs": {"devices": mode}}, body)

    def test_dry_run_never_posts_or_requests_confirmation(self):
        with patch("builtins.input", side_effect=AssertionError):
            m.launch(self.client, self.pr, "all", dry_run=True)
        self.client.request.assert_not_called()

    def test_changed_or_closed_pr_prevents_dispatch(self):
        for change in ("sha", "closed", "fork"):
            current = copy.deepcopy(self.pr)
            if change == "sha":
                current["head"]["sha"] = "new"
            elif change == "closed":
                current["state"] = "closed"
            else:
                current["head"]["repo"]["full_name"] = "other/repo"
            self.client.request.reset_mock()
            self.client.request.return_value = current
            with (
                patch("builtins.input", return_value="y"),
                self.assertRaisesRegex(RuntimeError, "PR changed"),
            ):
                m.launch(self.client, self.pr, "single")
            self.assertEqual(
                ["GET"], [call.args[0] for call in self.client.request.call_args_list]
            )

    def test_cancel_never_posts(self):
        with patch("builtins.input", return_value="n"):
            m.launch(self.client, self.pr, "single")
        self.client.request.assert_not_called()

    def test_confirmed_dispatch_posts_exactly_once(self):
        self.client.request.side_effect = [self.pr, None]
        with patch("builtins.input", return_value="y"):
            m.launch(self.client, self.pr, "single")
        self.assertEqual(
            ["GET", "POST"],
            [call.args[0] for call in self.client.request.call_args_list],
        )

    def test_fork_is_rejected(self):
        self.pr["head"]["repo"] = {"full_name": "other/repo"}
        with self.assertRaisesRegex(RuntimeError, "forks"):
            m.plan_run(self.client, self.pr, "all")

    def test_ci_does_not_rerun_old_commit_or_duplicate_active_run(self):
        run = {
            "id": 20,
            "head_sha": "abc",
            "head_branch": "feature/test",
            "head_repository": {"full_name": m.REPOSITORY},
            "status": "completed",
            "html_url": "https://github.com/run",
        }
        self.client.request.return_value = {"workflow_runs": [run]}
        self.assertEqual(
            "/actions/runs/20/rerun", m.plan_run(self.client, self.pr, "ci")[0]
        )
        run["head_sha"] = "old"
        with self.assertRaisesRegex(RuntimeError, "No CI run"):
            m.plan_run(self.client, self.pr, "ci")
        run.update(head_sha="abc", status="in_progress")
        with self.assertRaisesRegex(RuntimeError, "already queued or running"):
            m.plan_run(self.client, self.pr, "ci")

    def test_environment_token_does_not_need_gh(self):
        with (
            patch.dict(os.environ, {"GH_TOKEN": "test-token"}),
            patch.object(m.subprocess, "run", side_effect=AssertionError),
        ):
            self.assertEqual("test-token", m.github_token())

    def test_pr_list_paginates(self):
        client = m.GitHub(m.REPOSITORY, "test-token")
        with patch.object(
            client, "request", side_effect=[[self.pr] * 100, [self.pr]]
        ) as request:
            self.assertEqual(101, len(client.open_prs()))
        self.assertIn("page=2", request.call_args.args[1])


class GitHubRequestTest(unittest.TestCase):
    def test_dispatch_serializes_json_and_accepts_empty_success(self):
        client = m.GitHub(m.REPOSITORY, "test-token")
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b""
        body = {"ref": "feature/test", "inputs": {"devices": "single"}}
        with patch.object(
            m.urllib.request, "urlopen", return_value=response
        ) as urlopen:
            self.assertIsNone(
                client.request(
                    "POST", "/actions/workflows/selectel-ui.yml/dispatches", body
                )
            )
        request = urlopen.call_args.args[0]
        self.assertEqual(body, json.loads(request.data))
        self.assertEqual("Bearer test-token", request.get_header("Authorization"))
        self.assertEqual("POST", request.method)

    def test_ambiguous_post_failure_is_not_retried_or_leaked(self):
        client = m.GitHub(m.REPOSITORY, "test-token")
        for error in (
            TimeoutError("test-token"),
            urllib.error.HTTPError(
                "https://api.github.com", 503, "test-token", {}, None
            ),
        ):
            with patch.object(
                m.urllib.request, "urlopen", side_effect=error
            ) as urlopen:
                with self.assertRaisesRegex(
                    RuntimeError, "outcome is unknown"
                ) as raised:
                    client.request(
                        "POST", "/actions/workflows/selectel-ui.yml/dispatches", {}
                    )
            self.assertEqual(1, urlopen.call_count)
            self.assertNotIn("test-token", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
