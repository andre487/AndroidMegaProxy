import copy
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.request
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
GENERATED = {"store": NOTES, "github": NOTES}


class ReleaseTests(unittest.TestCase):
    def test_authenticated_notes_request_rejects_redirects(self):
        request = urllib.request.Request(
            "https://api.openai.com/v1/responses",
            data=b"{}",
            headers={"Authorization": "Bearer synthetic-test-key"},
        )
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                m.NoRedirect().redirect_request(
                    request, None, status, "redirect", {}, "https://other.example/"
                )

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
                    "content": [{"type": "output_text", "text": json.dumps(GENERATED)}],
                }
            ],
        }
        self.assertEqual(GENERATED, m.response_notes(good))
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

    def test_generator_sends_diff_at_requested_ref_and_keeps_distinct_outputs(self):
        from io import BytesIO

        generated = {
            "store": NOTES,
            "github": {
                "en-US": "- " + "Specific behavior " * 40 + ".",
                "ru-RU": "- " + "Конкретное изменение " * 40 + ".",
            },
        }
        response = {
            "status": "completed",
            "model": "test-model",
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": json.dumps(generated)}],
                }
            ],
        }
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(
                m,
                "command",
                side_effect=["Fix stale DNS", "-cachedAddress\n+freshAddress"],
            ) as run,
            patch.object(m.urllib.request, "build_opener") as opener,
        ):
            opener.return_value.open.return_value.__enter__.return_value = BytesIO(
                json.dumps(response).encode()
            )
            self.assertEqual(generated, m.generate_notes("1.1.3", "v1.1.2", "v1.1.3"))
            payload = json.loads(opener.return_value.open.call_args.args[0].data)
            evidence = json.loads(payload["input"])
            self.assertEqual("-cachedAddress\n+freshAddress", evidence["diff"])
            self.assertEqual("v1.1.3", evidence["head"])
            self.assertIn("v1.1.2..v1.1.3", run.call_args_list[0].args)
            self.assertIn("v1.1.3", run.call_args_list[1].args)
            self.assertIn("exactly one sentence per change", payload["instructions"])
            self.assertEqual(
                {"store", "github"},
                set(payload["text"]["format"]["schema"]["properties"]),
            )
        response["output"][0]["content"][0]["text"] = json.dumps(
            {"store": generated["github"], "github": NOTES}
        )
        with self.assertRaisesRegex(RuntimeError, "500 characters"):
            m.response_notes(response)
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "command", side_effect=["", "diff"]),
            patch.object(m.urllib.request, "build_opener") as opener,
            self.assertRaisesRegex(RuntimeError, "history is empty"),
        ):
            m.generate_notes("1.1.3", "v1.1.2", "v1.1.3")
        opener.assert_not_called()

    def test_large_release_context_keeps_every_character_and_combines_all_summaries(
        self,
    ):
        from io import BytesIO

        history = "Add tunnel upload; fix DNS"
        diff = "diff --git a/network.go b/network.go\n" + "новый код\n" * 70_000
        chunks = []
        final = []

        def respond(request, **kwargs):
            payload = json.loads(request.data)
            self.assertEqual("disabled", payload["truncation"])
            self.assertLessEqual(len(payload["input"]), m.RELEASE_CONTEXT_LIMIT)
            if "summary" in payload["text"]["format"]["schema"]["properties"]:
                chunks.append(payload["input"])
                content = {"summary": f"Concrete change from part {len(chunks)}."}
            else:
                final.append(json.loads(payload["input"]))
                content = GENERATED
            return BytesIO(
                json.dumps(
                    {
                        "status": "completed",
                        "output": [
                            {
                                "type": "message",
                                "content": [
                                    {"type": "output_text", "text": json.dumps(content)}
                                ],
                            }
                        ],
                    }
                ).encode()
            )

        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "command", side_effect=[history, diff]) as run,
            patch.object(m.urllib.request, "build_opener") as opener,
        ):
            opener.return_value.open.side_effect = respond
            self.assertEqual(
                GENERATED, m.generate_notes("1.2.0", "v1.1.3", "tested-head")
            )
        original = json.dumps(
            {
                "version": "1.2.0",
                "previous_tag": "v1.1.3",
                "head": "tested-head",
                "history": history,
                "diff": diff,
            },
            ensure_ascii=False,
        )
        self.assertGreater(len(chunks), 1)
        self.assertEqual(original, "".join(chunks))
        self.assertEqual(
            [f"Concrete change from part {i}." for i in range(1, len(chunks) + 1)],
            final[0]["summaries"],
        )
        self.assertEqual("tested-head", final[0]["head"])
        self.assertEqual(
            2, run.call_count
        )  # Reading Git evidence only; no branch/version/tag writes.

    def test_summary_over_target_is_preserved_and_used_in_final_notes(self):
        from contextlib import redirect_stdout
        from io import BytesIO, StringIO

        long_summary = "Concrete factual change.\n" * 1000
        self.assertGreater(len(long_summary), 20_000)
        requests = []

        def respond(request, **kwargs):
            payload = json.loads(request.data)
            requests.append(payload)
            self.assertLessEqual(len(payload["input"]), m.RELEASE_CONTEXT_LIMIT)
            content = (
                {"summary": long_summary}
                if payload["text"]["format"]["name"] == "release_facts"
                else GENERATED
            )
            return BytesIO(
                json.dumps(
                    {
                        "status": "completed",
                        "model": "returned-model",
                        "usage": {"output_tokens": 8000},
                        "output": [
                            {
                                "type": "message",
                                "content": [
                                    {"type": "output_text", "text": json.dumps(content)}
                                ],
                            }
                        ],
                    }
                ).encode()
            )

        output = StringIO()
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "command", side_effect=["history", "x" * 650_000]),
            patch.object(m.urllib.request, "build_opener") as opener,
            redirect_stdout(output),
        ):
            opener.return_value.open.side_effect = respond
            self.assertEqual(GENERATED, m.generate_notes("1.2.0", "v1.1.3"))
        self.assertEqual(3, len(requests))
        self.assertEqual(
            [long_summary, long_summary], json.loads(requests[-1]["input"])["summaries"]
        )
        self.assertIn('"summary_characters": 25000', output.getvalue())
        self.assertIn('"model": "returned-model"', output.getvalue())
        self.assertNotIn(long_summary, output.getvalue())

    def test_invalid_summary_logs_usage_and_length_without_content(self):
        from contextlib import redirect_stdout
        from io import BytesIO, StringIO

        output = StringIO()
        response = {
            "status": "completed",
            "model": "returned-model",
            "usage": {"output_tokens": 3},
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": '{"summary":""}'}],
                }
            ],
        }
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m.urllib.request, "build_opener") as opener,
            redirect_stdout(output),
            self.assertRaisesRegex(RuntimeError, "Empty or invalid release summary"),
        ):
            opener.return_value.open.return_value.__enter__.return_value = BytesIO(
                json.dumps(response).encode()
            )
            m.request_release_notes("source evidence", "v1.1.3", "HEAD", summarize=True)
        self.assertIn('"output_tokens": 3', output.getvalue())
        self.assertIn('"summary_characters": 0', output.getvalue())
        self.assertNotIn("synthetic-key", output.getvalue())
        self.assertNotIn("source evidence", output.getvalue())

    def test_context_partition_preserves_large_files_and_file_boundaries(self):
        for evidence in (
            "x" * 130,
            r"prefix\ndiff --git a/x b/x\n" + "ю" * 150,
            "😀" * 125,
        ):
            with (
                self.subTest(evidence=evidence[:20]),
                patch.object(m, "RELEASE_CONTEXT_LIMIT", 60),
            ):
                parts = m.release_context_parts(evidence)
                self.assertEqual(evidence, "".join(parts))
                self.assertTrue(all(0 < len(part) <= 60 for part in parts))
        with patch.object(m, "RELEASE_CONTEXT_LIMIT", 30):
            parts = m.release_context_parts(r"prefix\ndiff --git a/x b/x\n" + "x" * 60)
            self.assertEqual(r"prefix\n", parts[0])
            self.assertTrue(parts[1].startswith("diff --git "))

    def test_summary_rounds_reduce_context_until_final_request_fits(self):
        from io import BytesIO

        inputs = []

        def respond(request, **kwargs):
            payload = json.loads(request.data)
            inputs.append(payload["input"])
            self.assertLessEqual(len(payload["input"]), 300)
            content = (
                {"summary": "Factual change " * 3}
                if "summary" in payload["text"]["format"]["schema"]["properties"]
                else GENERATED
            )
            return BytesIO(
                json.dumps(
                    {
                        "status": "completed",
                        "output": [
                            {
                                "type": "message",
                                "content": [
                                    {"type": "output_text", "text": json.dumps(content)}
                                ],
                            }
                        ],
                    }
                ).encode()
            )

        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "RELEASE_CONTEXT_LIMIT", 300),
            patch.object(m, "command", side_effect=["history", "x" * 2200]),
            patch.object(m.urllib.request, "build_opener") as opener,
        ):
            opener.return_value.open.side_effect = respond
            self.assertEqual(GENERATED, m.generate_notes("1.2.0", "v1.1.3"))
        self.assertTrue(any('"summaries"' in text for text in inputs[:-1]))
        self.assertIn('"summaries"', inputs[-1])

    def test_invalid_or_failed_summary_aborts_before_release_writes(self):
        from io import BytesIO
        from urllib.error import HTTPError

        def response(content, **changes):
            return dict(
                status="completed",
                output=[{"type": "message", "content": content}],
                **changes,
            )

        for result in (
            response([{"type": "output_text", "text": json.dumps({"summary": ""})}]),
            response(
                [{"type": "output_text", "text": json.dumps({"summary": "  \n  "})}]
            ),
            response([{"type": "output_text", "text": json.dumps({"summary": None})}]),
            response([{"type": "output_text", "text": "{}"}]),
            response([{"type": "refusal"}]),
            {"status": "incomplete"},
        ):
            with (
                self.subTest(result=str(result)[:80]),
                patch.dict(
                    os.environ,
                    {
                        "OPENAI_API_KEY": "synthetic-key",
                        "OPENAI_RELEASE_MODEL": "test-model",
                    },
                ),
                patch.object(
                    m, "command", side_effect=["history", "x" * 600_001]
                ) as run,
                patch.object(m.urllib.request, "build_opener") as opener,
                self.assertRaises(RuntimeError),
            ):
                opener.return_value.open.return_value.__enter__.return_value = BytesIO(
                    json.dumps(result).encode()
                )
                m.generate_notes("1.2.0", "v1.1.3")
            self.assertEqual(2, run.call_count)
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "command", side_effect=["history", "x" * 600_001]) as run,
            patch.object(m.urllib.request, "build_opener") as opener,
            self.assertRaisesRegex(RuntimeError, "HTTP 429; no release files written"),
        ):
            good = {
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "content": [
                            {
                                "type": "output_text",
                                "text": json.dumps({"summary": "Earlier part facts."}),
                            }
                        ],
                    }
                ],
            }
            opener.return_value.open.side_effect = [
                BytesIO(json.dumps(good).encode()),
                HTTPError(
                    "https://api.openai.com/v1/responses", 429, "quota", {}, None
                ),
            ]
            m.generate_notes("1.2.0", "v1.1.3")
        self.assertEqual(2, run.call_count)
        self.assertEqual(2, opener.return_value.open.call_count)

    def test_nonreducing_summaries_stop_instead_of_looping(self):
        with (
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-key",
                    "OPENAI_RELEASE_MODEL": "test-model",
                },
            ),
            patch.object(m, "RELEASE_CONTEXT_LIMIT", 300),
            patch.object(m, "command", side_effect=["history", "x" * 500]),
            patch.object(m, "request_release_notes", return_value="x" * 400) as request,
            self.assertRaisesRegex(RuntimeError, "did not reduce context"),
        ):
            m.generate_notes("1.2.0", "v1.1.3")
        self.assertTrue(
            all(call.kwargs.get("summarize") for call in request.call_args_list)
        )

    def test_previous_release_skips_unpublished_incomplete_and_unreachable_tags(self):
        def release(tag, **changes):
            assets = [
                {"name": f"mega-proxy-{variant}.apk", "state": "uploaded", "size": 123}
                for variant in (
                    "universal",
                    "arm64-v8a",
                    "armeabi-v7a",
                    "x86",
                    "x86_64",
                )
            ]
            assets.append({"name": "SHA256SUMS", "state": "uploaded", "size": 123})
            return (
                dict(tag_name=tag, draft=False, prerelease=False, assets=assets)
                | changes
            )

        good = release("v1.1.1")
        bad = [
            release("v1.1.2", assets=[]),
            release("v1.1.2", assets=good["assets"][:-1]),
            release("v1.1.2", draft=True),
            release("v1.1.2", prerelease=True),
            release("v1.1.2", assets=[dict(a, size=0) for a in good["assets"]]),
            release("v1.1.2", assets=[dict(a, state="new") for a in good["assets"]]),
        ]
        for broken in bad:
            with (
                self.subTest(broken=broken),
                patch.object(m, "command", return_value="v1.1.1\nv1.1.2\nv1.1.3"),
                patch.object(
                    m,
                    "api",
                    return_value=[release("v1.1.3"), broken, release("v1.0.0"), good],
                ),
            ):
                self.assertEqual("v1.1.1", m.previous_release("1.1.3", "v1.1.3"))
        with (
            patch.object(m, "command", return_value="v1.1.1\nv1.1.2"),
            patch.object(
                m, "api", side_effect=[[release("v1.1.2", assets=[])] * 100, [good]]
            ) as api,
        ):
            self.assertEqual("v1.1.1", m.previous_release("1.1.3"))
            self.assertEqual("releases?per_page=100&page=2", api.call_args.args[0])
        with (
            patch.object(m, "command", return_value="v1.1.2"),
            patch.object(m, "api", return_value=[release("v1.1.2", assets=[])]),
            self.assertRaisesRegex(RuntimeError, "No previous published"),
        ):
            m.previous_release("1.1.3")

    def test_preview_prints_both_formats_without_release_mutations(self):
        from io import StringIO

        with (
            patch.object(
                sys,
                "argv",
                [
                    "release_automation.py",
                    "preview",
                    "--version",
                    "1.1.3",
                    "--previous",
                    "v1.1.2",
                    "--ref",
                    "v1.1.3",
                ],
            ),
            patch.object(m, "generate_notes", return_value=GENERATED) as generate,
            patch.object(
                m, "command", side_effect=AssertionError("Preview must not mutate git")
            ),
            patch.object(sys, "stdout", new_callable=StringIO) as output,
        ):
            self.assertEqual(0, m.main())
            generate.assert_called_once_with("1.1.3", "v1.1.2", "v1.1.3")
            self.assertIn("### F-Droid", output.getvalue())
            self.assertEqual(2, output.getvalue().count(NOTES["ru-RU"]))

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
                "steps": [
                    {"name": m.BASE_STEP + "b" * 40, "conclusion": "success"},
                    {"name": "Pull request: 7", "conclusion": "success"},
                ],
            }
            for name in ["Change scope", *m.CHECKS.values()]
        ]
        m.full_ci(run, jobs, "a" * 40, "b" * 40, 7)
        m.full_ci(dict(run, pull_requests=[]), jobs, "a" * 40, "b" * 40, 7)
        missing_identity = copy.deepcopy(jobs)
        missing_identity[0]["steps"] = missing_identity[0]["steps"][:1]
        with self.assertRaisesRegex(RuntimeError, "another pull request"):
            m.full_ci(
                dict(run, pull_requests=[]), missing_identity, "a" * 40, "b" * 40, 7
            )

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

    def test_ci_wait_uses_recorded_identity_when_merged_run_loses_pr_links(self):
        head, base = "a" * 40, "b" * 40
        pr = {
            "head": {
                "sha": head,
                "ref": "release/v0.1.2",
                "repo": {"full_name": "owner/repo"},
            },
            "base": {"sha": base, "ref": "main", "repo": {"full_name": "owner/repo"}},
            "draft": False,
            "state": "closed",
            "merged": True,
        }
        run = {
            "id": 1,
            "event": "pull_request",
            "head_sha": head,
            "head_branch": "release/v0.1.2",
            "head_repository": {"full_name": "owner/repo"},
            "pull_requests": [],
            "status": "completed",
            "conclusion": "success",
        }
        jobs = [
            {
                "name": name,
                "conclusion": "success",
                "steps": [
                    {"name": m.BASE_STEP + base, "conclusion": "success"},
                    {"name": "Pull request: 7", "conclusion": "success"},
                ],
            }
            for name in ["Change scope", *m.CHECKS.values()]
        ]
        with (
            patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}),
            patch.object(
                m, "api", side_effect=[pr, {"workflow_runs": [run]}, {"jobs": jobs}]
            ),
            patch.object(m.time, "sleep", side_effect=AssertionError),
        ):
            m.wait_ci(7, "0.1.2", head, base)

    def test_prepare_merge_and_retry_tag_with_real_git_and_fake_services(self):
        for squash in (False, True):
            with self.subTest(squash=squash):
                self.prepare_merge_and_retry_tag(squash)

    def prepare_merge_and_retry_tag(self, squash):
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
            (work / ".gitignore").write_text(
                (Path(__file__).parents[2] / ".gitignore").read_text()
            )
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
                if path == "releases?per_page=100&page=1":
                    return [
                        {
                            "tag_name": "v0.1.0",
                            "draft": False,
                            "prerelease": False,
                            "assets": [
                                {"name": name, "state": "uploaded", "size": 123}
                                for name in [
                                    "SHA256SUMS",
                                    *[
                                        f"mega-proxy-{v}.apk"
                                        for v in (
                                            "universal",
                                            "arm64-v8a",
                                            "armeabi-v7a",
                                            "x86",
                                            "x86_64",
                                        )
                                    ],
                                ]
                            ],
                        }
                    ]
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
                if path.startswith("pulls?state=closed&"):
                    return [dict(copy.deepcopy(pr), merged_at="2026-10-24T17:56:57Z")]
                if path == "pulls/7":
                    return copy.deepcopy(pr)
                if path == "pulls/7/merge":
                    self.assertEqual(
                        {"sha": pr["head"]["sha"], "merge_method": "merge"}, payload
                    )
                    self.assertEqual("PUT", method)
                    git("switch", "-q", "main")
                    if squash:
                        git("merge", "--squash", pr["head"]["sha"])
                        git("commit", "-qm", "Squashed release v0.1.2")
                    else:
                        git(
                            "merge",
                            "--no-ff",
                            "-qm",
                            "Release v0.1.2",
                            pr["head"]["sha"],
                        )
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
                    patch.object(m, "generate_notes", return_value=GENERATED),
                    patch.object(m, "emit"),
                ):
                    # ruby/setup-ruby creates these before release preparation.
                    for name in (
                        ".bundle/config",
                        "vendor/bundle/ruby/gems/fixture.rb",
                    ):
                        path = work / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text("Bundler fixture\n")
                    self.assertEqual("", git("status", "--porcelain"))
                    for path in (work / m.GRADLE, work / "unexpected.txt"):
                        original = path.read_text() if path.exists() else None
                        path.write_text("Uncommitted change\n")
                        with self.assertRaisesRegex(
                            RuntimeError, "Use a clean checkout"
                        ):
                            m.prepare("0.1.2")
                        self.assertEqual([], calls)
                        if original is None:
                            path.unlink()
                        else:
                            path.write_text(original)
                    m.prepare("0.1.2")
                    head = pr["head"]["sha"]
                    self.assertEqual(
                        m.render_notes(GENERATED["github"]),
                        (work / m.release_notes_path("0.1.2")).read_text(),
                    )
                    self.assertEqual(
                        "Historical note\n",
                        (work / m.notes_paths(14000)[0]).read_text(),
                    )
                    self.assertEqual(
                        set(
                            [
                                str(m.GRADLE),
                                *map(str, m.notes_paths(15000)),
                                str(m.release_notes_path("0.1.2")),
                            ]
                        ),
                        set(git("diff", "--name-only", base, head).splitlines()),
                    )
                    for locale in m.LOCALES:
                        for offset in range(5):
                            self.assertEqual(
                                NOTES[locale] + "\n",
                                (
                                    work
                                    / f"fastlane/metadata/android/{locale}/changelogs/{15000 + offset}.txt"
                                ).read_text(),
                            )
                    original_command = m.command
                    wrong_path = (
                        f"{head}:fastlane/metadata/android/en-US/changelogs/15004.txt"
                    )
                    with (
                        patch.object(
                            m,
                            "command",
                            side_effect=lambda *args, **kwargs: (
                                "Different notes"
                                if args == ("git", "show", wrong_path)
                                else original_command(*args, **kwargs)
                            ),
                        ),
                        patch.object(m, "wait_ci") as ci,
                        self.assertRaisesRegex(
                            RuntimeError, "Variant changelog differs"
                        ),
                    ):
                        m.finish("0.1.2", 7, head)
                    ci.assert_not_called()
                    self.assertNotIn("pulls/7/merge", calls)
                    self.assertNotIn("git/refs", calls)
                    with (
                        patch.object(
                            m,
                            "command",
                            side_effect=lambda *args, **kwargs: (
                                "## English\n\n- Missing translation."
                                if args
                                == (
                                    "git",
                                    "show",
                                    f"{head}:{m.release_notes_path('0.1.2')}",
                                )
                                else original_command(*args, **kwargs)
                            ),
                        ),
                        patch.object(m, "wait_ci") as ci,
                        self.assertRaisesRegex(
                            RuntimeError, "Missing Russian release notes"
                        ),
                    ):
                        m.finish("0.1.2", 7, head)
                    ci.assert_not_called()
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

                    def complete_ci(*args):
                        if squash and not pr["merged"]:
                            # A user can squash-merge while finalization waits for CI.
                            fake_api(
                                "pulls/7/merge",
                                {"sha": head, "merge_method": "merge"},
                                "PUT",
                            )

                    with patch.object(m, "wait_ci", side_effect=complete_ci):
                        m.finish("0.1.2", 7, head)
                        self.assertEqual(
                            pr["merge_commit_sha"],
                            git("rev-parse", "refs/tags/v0.1.2", cwd=remote),
                        )
                        self.assertNotEqual(head, pr["merge_commit_sha"])
                        self.assertEqual(
                            [base] if squash else [base, head],
                            git(
                                "show", "-s", "--format=%P", pr["merge_commit_sha"]
                            ).split(),
                        )
                        merged_commit = pr["merge_commit_sha"]
                        pr["merge_commit_sha"] = base
                        with self.assertRaisesRegex(RuntimeError, "verified base"):
                            m.finish("0.1.2", 7, head)
                        # Even a valid parent cannot authorize untested content.
                        pr["merge_commit_sha"] = git(
                            "commit-tree",
                            f"{base}^{{tree}}",
                            "-p",
                            base,
                            "-m",
                            "Wrong release tree",
                        )
                        with self.assertRaisesRegex(
                            RuntimeError, "Merged tree differs"
                        ):
                            m.finish("0.1.2", 7, head)
                        pr["merge_commit_sha"] = merged_commit
                        m.finish("0.1.2", 7, head)
                        self.assertEqual(1, calls.count("pulls/7/merge"))
                        self.assertEqual(1, calls.count("git/refs"))
                        # Same-version preparation resumes the original PR only without a tag.
                        with self.assertRaisesRegex(
                            RuntimeError, "Release tag already exists"
                        ):
                            m.prepare("0.1.2")
                        git("update-ref", "-d", "refs/tags/v0.1.2", cwd=remote)
                        before = git("rev-parse", "HEAD")
                        with (
                            patch.object(m, "generate_notes") as generate,
                            patch.object(m, "emit") as emit,
                        ):
                            m.prepare("0.1.2")
                        emit.assert_called_once_with(pr=7, head=head)
                        generate.assert_not_called()
                        self.assertEqual(before, git("rev-parse", "HEAD"))
                        self.assertEqual("", git("status", "--porcelain"))
                        with patch.object(
                            m,
                            "api",
                            side_effect=lambda path, *args, **kwargs: (
                                []
                                if path.startswith("pulls?")
                                else fake_api(path, *args, **kwargs)
                            ),
                        ):
                            with self.assertRaisesRegex(
                                RuntimeError, "one merged release PR"
                            ):
                                m.prepare("0.1.2")
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
