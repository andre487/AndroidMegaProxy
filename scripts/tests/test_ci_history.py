import copy
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import ci_history as m


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.base = "b" * 40
        self.runs = [self.run_data(30, "3" * 40), self.run_data(20, "2" * 40)]
        self.jobs = {
            30: self.job_data("failure", "skipped", "success"),
            20: self.job_data("success", "success", "success"),
        }
        self.ancestor = Mock(return_value=True)

    def run_data(self, number, sha):
        return {
            "id": number,
            "head_sha": sha,
            "head_branch": "feature/test",
            "pull_requests": [
                {"number": 32, "head": {"repo": {"id": 1}}, "base": {"repo": {"id": 1}}}
            ],
        }

    def job_data(self, android, native, python):
        return [
            {
                "name": "Change scope",
                "conclusion": "success",
                "steps": [{"name": m.BASE_STEP + self.base, "conclusion": "success"}],
            }
        ] + [
            {"name": name, "conclusion": result}
            for name, result in zip(m.CHECKS.values(), (android, native, python))
        ]

    def read(self, endpoint):
        if "/workflows/" in endpoint:
            return {"workflow_runs": self.runs}
        return {"jobs": self.jobs[int(endpoint.split("/runs/")[1].split("/")[0])]}

    def select(self):
        return m.successful_baselines(
            "owner/repo",
            "feature/test",
            32,
            self.base,
            "h" * 40,
            40,
            self.read,
            self.ancestor,
        )

    def test_each_suite_uses_its_last_actual_success(self):
        result = self.select()
        self.assertEqual(20, result["android"]["run_id"])
        self.assertEqual(20, result["native"]["run_id"])
        self.assertEqual(30, result["python"]["run_id"])

    def test_cancelled_failed_and_skipped_jobs_do_not_advance_baseline(self):
        self.jobs[30] = self.job_data("cancelled", "failure", "skipped")
        self.assertEqual({20}, {item["run_id"] for item in self.select().values()})

    def test_other_base_or_missing_marker_is_not_reused(self):
        self.jobs[30][0]["steps"][0]["name"] = m.BASE_STEP + "other-base"
        self.jobs[20][0]["steps"] = []
        self.assertEqual({}, self.select())

    def test_nonancestor_is_not_reused(self):
        self.ancestor.return_value = False
        self.assertEqual({}, self.select())

    def test_current_future_other_pr_branch_and_fork_are_excluded(self):
        original = copy.deepcopy(self.runs[0])
        for kind in ("current", "future", "pr", "branch", "fork"):
            run = copy.deepcopy(original)
            if kind == "current":
                run["id"] = 40
            if kind == "future":
                run["id"] = 50
            if kind == "pr":
                run["pull_requests"][0]["number"] = 99
            if kind == "branch":
                run["head_branch"] = "other"
            if kind == "fork":
                run["pull_requests"][0]["head"]["repo"]["id"] = 2
            self.runs = [run]
            self.assertEqual({}, self.select(), kind)

    def test_gh_failure_does_not_expose_token_or_response(self):
        with patch.object(
            m.subprocess, "run", return_value=Mock(returncode=1, stderr="secret")
        ) as run:
            with self.assertRaisesRegex(
                RuntimeError, "history is unavailable"
            ) as raised:
                m.github_json("repos/owner/repo/actions/runs")
        self.assertNotIn("secret", str(raised.exception))
        self.assertEqual(
            ["gh", "api", "repos/owner/repo/actions/runs"], run.call_args.args[0]
        )
