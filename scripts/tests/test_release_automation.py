import copy
import importlib.util
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
    "release_automation", Path(__file__).parents[1] / "release_automation.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

SOURCE = 'val versionCodeBase = 14\nval abiOffset = 2\nandroid {\n    versionCode = versionCodeBase * 1000 + abiOffset\n    versionName = "0.1.1"\n}\n'
NOTES = {"en-US": "- Improved connections.", "ru-RU": "- Улучшено подключение."}


class ReleaseTests(unittest.TestCase):
    def test_api_mutations_use_explicit_http_method_and_structured_stdin(self):
        with (
            patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}),
            patch.object(m, "command", return_value="{}") as run,
        ):
            m.api("git/refs", {"ref": "refs/tags/v1.0.0", "sha": "a" * 40})
            run.assert_called_once_with(
                "gh",
                "api",
                "repos/owner/repo/git/refs",
                "--method",
                "POST",
                "--input",
                "-",
                input_text=json.dumps({"ref": "refs/tags/v1.0.0", "sha": "a" * 40}),
            )

    def test_version_increment_preserves_fdroid_abi_layout(self):
        updated, code = m.bump_version(SOURCE, "0.1.2")
        self.assertEqual(15000, code)
        self.assertEqual(("0.1.2", 15), m.read_version(updated))
        self.assertEqual(SOURCE.replace("14", "15").replace("0.1.1", "0.1.2"), updated)
        for version in [
            "0.1.1",
            "0.0.9",
            "v0.1.2",
            "01.2.3",
            "1.2.3-rc1",
            "1.2.3\n",
            "$(id)",
            "1/2/3",
        ]:
            with self.subTest(version=version), self.assertRaises(RuntimeError):
                m.bump_version(SOURCE, version)
        for source in [SOURCE + SOURCE, SOURCE.replace("14", "2099999")]:
            with self.assertRaises(RuntimeError):
                m.bump_version(source, "1.0.0")

    def test_refusal_incomplete_and_invalid_ai_output_never_become_notes(self):
        good = {
            "status": "completed",
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": json.dumps(NOTES)}],
                }
            ],
        }
        self.assertEqual(NOTES, m.response_notes(good))
        for response in [
            dict(good, status="incomplete"),
            {
                "status": "completed",
                "output": [{"type": "message", "content": [{"type": "refusal"}]}],
            },
        ]:
            with self.assertRaises(RuntimeError):
                m.response_notes(response)
        for notes in [
            {},
            dict(NOTES, extra="x"),
            dict(NOTES, **{"en-US": "x" * 501}),
            dict(NOTES, **{"ru-RU": "English"}),
            dict(NOTES, **{"en-US": "hello\x00"}),
        ]:
            with self.assertRaises(RuntimeError):
                m.validate_notes(notes)

    def test_changed_draft_closed_and_fork_prs_stop_before_fetch_or_merge(self):
        pr = {
            "head": {
                "sha": "a" * 40,
                "ref": "release/v0.1.2",
                "repo": {"full_name": "owner/repo"},
            },
            "base": {"ref": "main", "repo": {"full_name": "owner/repo"}},
            "draft": False,
            "state": "open",
            "merged": False,
        }
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}):
            for field, value in [
                ("sha", "b" * 40),
                ("ref", "feature/evil"),
                ("repo", {"full_name": "fork/repo"}),
            ]:
                bad = copy.deepcopy(pr)
                bad["head"][field] = value
                with (
                    patch.object(m, "api", return_value=bad),
                    patch.object(m, "command", side_effect=AssertionError),
                    self.assertRaises(RuntimeError),
                ):
                    m.finish("0.1.2", 7, "a" * 40)
            for bad in [dict(pr, draft=True), dict(pr, state="closed")]:
                with (
                    patch.object(m, "api", return_value=bad),
                    patch.object(m, "command", side_effect=AssertionError),
                    self.assertRaises(RuntimeError),
                ):
                    m.finish("0.1.2", 7, "a" * 40)

    def test_ci_requires_each_success_on_exact_pr_head_and_recorded_base(self):
        run = {
            "event": "pull_request",
            "head_sha": "a" * 40,
            "pull_requests": [{"number": 7}],
            "conclusion": "success",
        }
        jobs = [
            {
                "name": name,
                "conclusion": "success",
                "steps": [{"name": m.BASE_STEP + "b" * 40, "conclusion": "success"}],
            }
            for name in ["Change scope", *m.CHECKS.values()]
        ]
        m.full_ci(run, jobs, "a" * 40, "b" * 40, 7)
        for conclusion in ["skipped", "neutral", "failure", "cancelled", None]:
            for i in range(len(jobs)):
                changed = copy.deepcopy(jobs)
                changed[i]["conclusion"] = conclusion
                with (
                    self.subTest(job=i, conclusion=conclusion),
                    self.assertRaises(RuntimeError),
                ):
                    m.full_ci(run, changed, "a" * 40, "b" * 40, 7)
        for kwargs in [
            {"head_sha": "c" * 40},
            {"event": "push"},
            {"pull_requests": [{"number": 8}]},
        ]:
            with self.assertRaises(RuntimeError):
                m.full_ci(dict(run, **kwargs), jobs, "a" * 40, "b" * 40, 7)
        for changed in [jobs[:-1], jobs + [jobs[0]]]:
            with self.assertRaises(RuntimeError):
                m.full_ci(run, changed, "a" * 40, "b" * 40, 7)
        with self.assertRaises(RuntimeError):
            m.full_ci(run, jobs, "a" * 40, "c" * 40, 7)

    def test_prepare_merge_and_retry_tag_with_real_git_and_fake_services(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "GITHUB_REPOSITORY": "owner/repo",
                    "GITHUB_STEP_SUMMARY": "",
                    "GITHUB_OUTPUT": "",
                },
            ),
        ):
            root = Path(tmp)
            remote, work = root / "remote.git", root / "work"

            def git(*args, cwd=work):
                return subprocess.check_output(
                    ["git", *args], cwd=cwd, text=True, stderr=subprocess.DEVNULL
                ).strip()

            remote.mkdir()
            git("init", "--bare", "-q", cwd=remote)
            work.mkdir()
            git("init", "-q", "-b", "main")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            git("config", "commit.gpgsign", "false")
            git("remote", "add", "origin", str(remote))
            (work / "app").mkdir()
            (work / m.GRADLE).write_text(SOURCE)
            for locale in m.LOCALES:
                path = work / f"fastlane/metadata/android/{locale}/changelogs"
                path.mkdir(parents=True)
                (path / "14000.txt").write_text("Historical note\n")
            git("add", ".")
            git("commit", "-qm", "base")
            git("tag", "v0.1.0")
            git("push", "-q", "origin", "main", "--tags")
            base = git("rev-parse", "HEAD")
            pr = {}
            calls = []

            def fake_api(path, payload=None, method=None):
                calls.append(path)
                if path == "git/ref/heads/main":
                    return {
                        "object": {
                            "sha": git("rev-parse", "refs/heads/main", cwd=remote)
                        }
                    }
                if path == "pulls":
                    head = git("rev-parse", "HEAD")
                    git("push", "-q", "origin", f"{head}:refs/pull/7/head")
                    pr.update(
                        number=7,
                        html_url="https://github.com/owner/repo/pull/7",
                        draft=False,
                        state="open",
                        merged=False,
                        head={
                            "sha": head,
                            "ref": "release/v0.1.2",
                            "repo": {"full_name": "owner/repo"},
                        },
                        base={
                            "sha": base,
                            "ref": "main",
                            "repo": {"full_name": "owner/repo"},
                        },
                    )
                    return copy.deepcopy(pr)
                if path == "pulls/7":
                    return copy.deepcopy(pr)
                if path == "pulls/7/merge":
                    self.assertEqual(
                        {"sha": pr["head"]["sha"], "merge_method": "squash"}, payload
                    )
                    self.assertEqual("PUT", method)
                    git("switch", "-q", "main")
                    git("merge", "--squash", pr["head"]["sha"])
                    git("commit", "-qm", "Release v0.1.2")
                    git("push", "-q", "origin", "main")
                    pr.update(
                        merged=True,
                        state="closed",
                        merge_commit_sha=git("rev-parse", "HEAD"),
                    )
                    return {"merged": True}
                if path == "git/refs":
                    self.assertTrue(pr["merged"])
                    self.assertEqual(pr["merge_commit_sha"], payload["sha"])
                    git("update-ref", payload["ref"], payload["sha"], cwd=remote)
                    return {"ref": payload["ref"]}
                raise AssertionError(path)

            original_cwd = Path.cwd()
            os.chdir(work)
            try:
                with (
                    patch.object(m, "api", side_effect=fake_api),
                    patch.object(m, "generate_notes", return_value=NOTES),
                    patch.object(m, "emit"),
                ):
                    m.prepare("0.1.2")
                    head = pr["head"]["sha"]
                    self.assertEqual(
                        "Historical note\n",
                        (work / m.notes_paths(14000)[0]).read_text(),
                    )
                    self.assertEqual(
                        set([str(m.GRADLE), *map(str, m.notes_paths(15000))]),
                        set(git("diff", "--name-only", base, head).splitlines()),
                    )
                    # A failed CI gate must not merge or tag.
                    with (
                        patch.object(
                            m, "wait_ci", side_effect=RuntimeError("CI failed")
                        ),
                        self.assertRaisesRegex(RuntimeError, "CI failed"),
                    ):
                        m.finish("0.1.2", 7, head)
                    self.assertNotIn("pulls/7/merge", calls)
                    self.assertNotIn("git/refs", calls)

                    def advanced_main(path, payload=None, method=None):
                        if path == "git/ref/heads/main":
                            return {"object": {"sha": "b" * 40}}
                        return fake_api(path, payload, method)

                    with (
                        patch.object(m, "wait_ci"),
                        patch.object(m, "api", side_effect=advanced_main),
                        self.assertRaisesRegex(RuntimeError, "main advanced"),
                    ):
                        m.finish("0.1.2", 7, head)
                    self.assertNotIn("pulls/7/merge", calls)
                    with patch.object(m, "wait_ci"):
                        m.finish("0.1.2", 7, head)
                        self.assertEqual(
                            pr["merge_commit_sha"],
                            git("rev-parse", "refs/tags/v0.1.2", cwd=remote),
                        )
                        self.assertNotEqual(head, pr["merge_commit_sha"])
                        m.finish("0.1.2", 7, head)
                        self.assertEqual(1, calls.count("pulls/7/merge"))
                        self.assertEqual(1, calls.count("git/refs"))
                        # Never move an existing tag, even on a successful rerun.
                        git("update-ref", "refs/tags/v0.1.2", base, cwd=remote)
                        with self.assertRaisesRegex(RuntimeError, "elsewhere"):
                            m.finish("0.1.2", 7, head)
                        self.assertEqual(
                            base, git("rev-parse", "refs/tags/v0.1.2", cwd=remote)
                        )
            finally:
                os.chdir(original_cwd)


if __name__ == "__main__":
    unittest.main()
