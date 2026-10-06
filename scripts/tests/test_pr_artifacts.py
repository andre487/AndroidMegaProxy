import json
import subprocess
import textwrap
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).parents[2] / ".github/workflows/pr-artifacts-description.yml"
SCRIPT = textwrap.dedent(WORKFLOW.read_text().split("script: |\n", 1)[1])
HARNESS = """
const script = JSON.parse(process.argv[1]);
const options = JSON.parse(process.argv[2]);
const run = {id: 10, run_number: 20, run_attempt: 1, head_sha: 'abc',
  html_url: 'https://example/run', pull_requests: [{number: 7}]};
const result = {updates: [], errors: []};
const github = {
  rest: {
    actions: {listJobsForWorkflowRun: 'jobs', listWorkflowRunArtifacts: 'artifacts'},
    pulls: {
      get: async () => ({data: {body: options.body ?? '', state: 'open',
        head: {sha: options.head ?? 'abc'}}}),
      update: async value => { result.updates.push(value); }
    }
  },
  paginate: async endpoint => endpoint === 'jobs'
    ? [{name: 'Android tests and checks', conclusion: options.skipped ? 'skipped' : 'success'}]
    : ['debug-apk', 'unsigned-release-apk'].map((suffix, id) =>
      ({id, name: 'megaproxy-pr-7-' + suffix, expired: options.expired ?? false}))
};
const context = {repo: {owner: 'owner', repo: 'repo'}, serverUrl: 'https://github.com',
  payload: {workflow_run: run}};
const core = {notice() {}, setFailed: message => result.errors.push(message)};
const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
new AsyncFunction('github', 'context', 'core', script)(github, context, core)
  .then(() => process.stdout.write(JSON.stringify(result)))
  .catch(error => {console.error(error); process.exitCode = 1;});
"""


class ArtifactDescriptionTest(unittest.TestCase):
    def run_workflow(self, **options):
        output = subprocess.check_output(
            ["node", "-e", HARNESS, json.dumps(SCRIPT), json.dumps(options)],
            text=True,
        )
        return json.loads(output)

    def test_append_replace_and_preserve_author_text(self):
        first = self.run_workflow(body="Author description\n\n")
        self.assertFalse(first["errors"])
        body = first["updates"][0]["body"]
        self.assertTrue(
            body.startswith("Author description\n\n<!-- megaproxy-pr-apks -->")
        )
        self.assertTrue(body.endswith("<!-- /megaproxy-pr-apks -->"))
        self.assertIn("/actions/runs/10/artifacts/0", body)
        second = self.run_workflow(body=body + "\n\nAuthor follow-up")
        new_body = second["updates"][0]["body"]
        self.assertIn("Author follow-up\n\n<!-- megaproxy-pr-apks -->", new_body)
        self.assertEqual(new_body.count("## APK artifacts"), 1)
        self.assertEqual(
            self.run_workflow(body=new_body)["updates"][0]["body"], new_body
        )
        self.assertTrue(self.run_workflow()["updates"][0]["body"].startswith("<!--"))

    def test_stale_head_newer_run_and_skipped_build_do_not_write(self):
        for options in [
            {"head": "new"},
            {"skipped": True},
            {"body": "<!-- megaproxy-pr-apks-run: 21:1 -->"},
            {"body": "<!-- megaproxy-pr-apks-run: 20:2 -->"},
        ]:
            with self.subTest(options=options):
                result = self.run_workflow(**options)
                self.assertEqual(result["updates"], [])
                self.assertEqual(result["errors"], [])

    def test_missing_artifacts_and_broken_markers_fail_without_overwriting(self):
        for options in [
            {"expired": True},
            {"body": "Author text\n<!-- megaproxy-pr-apks -->\nEdited block"},
        ]:
            with self.subTest(options=options):
                result = self.run_workflow(**options)
                self.assertEqual(result["updates"], [])
                self.assertTrue(result["errors"])


if __name__ == "__main__":
    unittest.main()
