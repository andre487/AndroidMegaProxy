import json
import subprocess
import textwrap
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).parents[2] / ".github/workflows/test-reports.yml"
SCRIPT = textwrap.dedent(
    WORKFLOW.read_text().split("<<'JS'\n", 1)[1].split("\n          JS", 1)[0]
)
HARNESS = """
const vm = require('node:vm');
const script = JSON.parse(process.argv[1]);
const options = JSON.parse(process.argv[2]);
const result = {calls: [], delays: [], cancelled: 0, warnings: []};
const statuses = options.statuses || [200];
const globals = {fetch: async (url, init) => {
  result.calls.push({url, ...init});
  if (options.error) throw new Error(options.error);
  return {status: statuses.shift(), body: {cancel: async () => result.cancelled++}};
}};
vm.runInNewContext(script, {
  globalThis: globals, URL, Request, process: {env: {GITHUB_API_URL: 'https://api.github.com'}},
  require: name => {
    if (name !== 'node:timers/promises') throw new Error('Unexpected dependency');
    return {setTimeout: async delay => result.delays.push(delay)};
  },
  console: {warn: warning => result.warnings.push(warning)}
});
(async () => {
  try {
    const response = await globals.fetch(
      options.url || 'https://api.github.com/repos/owner/repo/check-runs/123',
      {method: options.method || 'PATCH', body: options.body ?? '{"conclusion":"success"}'}
    );
    result.status = response.status;
  } catch (error) { result.error = error.message; }
  process.stdout.write(JSON.stringify(result));
})();
"""


class TestReportRetryTests(unittest.TestCase):
    def run_retry(self, **options):
        return json.loads(
            subprocess.check_output(
                ["node", "-e", HARNESS, json.dumps(SCRIPT), json.dumps(options)],
                text=True,
                timeout=10,
            )
        )

    def test_transient_404_retries_the_same_update_without_creating_a_check(self):
        result = self.run_retry(statuses=[404, 404, 200])
        self.assertEqual(result["status"], 200)
        self.assertEqual(result["delays"], [1000, 3000])
        self.assertEqual(result["cancelled"], 2)
        self.assertEqual(len(result["calls"]), 3)
        self.assertTrue(all(c == result["calls"][0] for c in result["calls"]))
        self.assertEqual(result["calls"][0]["method"], "PATCH")

    def test_a_failed_test_result_is_not_changed_to_success(self):
        body = '{"status":"completed","conclusion":"failure"}'
        result = self.run_retry(statuses=[404, 200], body=body)
        self.assertEqual(result["status"], 200)
        self.assertTrue(all(c["body"] == body for c in result["calls"]))

    def test_exhaustion_preserves_the_final_404(self):
        result = self.run_retry(statuses=[404, 404, 404, 200])
        self.assertEqual(result["status"], 404)
        self.assertEqual(len(result["calls"]), 3)
        self.assertEqual(result["delays"], [1000, 3000])

    def test_other_responses_and_network_errors_are_not_retried(self):
        for status in [200, 401, 403, 422, 500]:
            with self.subTest(status=status):
                result = self.run_retry(statuses=[status])
                self.assertEqual(result["status"], status)
                self.assertEqual(len(result["calls"]), 1)
                self.assertEqual(result["delays"], [])
        result = self.run_retry(error="aborted")
        self.assertEqual(result["error"], "aborted")
        self.assertEqual(len(result["calls"]), 1)
        self.assertEqual(result["delays"], [])

    def test_creation_other_endpoints_and_non_replayable_bodies_are_not_retried(self):
        for options in [
            {"method": "POST", "url": "https://api.github.com/repos/o/r/check-runs"},
            {"method": "GET"},
            {"url": "https://api.github.com/repos/o/r/issues/123"},
            {"url": "https://other.example/repos/o/r/check-runs/123"},
            {"body": {"stream": True}},
        ]:
            with self.subTest(options=options):
                result = self.run_retry(statuses=[404, 200], **options)
                self.assertEqual(result["status"], 404)
                self.assertEqual(len(result["calls"]), 1)
                self.assertEqual(result["delays"], [])

    def test_preload_is_scoped_to_check_publication(self):
        workflow = WORKFLOW.read_text()
        publication, summary = workflow.split(
            "      - name: Add collapsible test groups to Actions Summary", 1
        )
        self.assertIn(
            "NODE_OPTIONS: --require=${{ runner.temp }}/test-report-check-retry.cjs",
            publication,
        )
        self.assertIn('cat > "$RUNNER_TEMP/test-report-check-retry.cjs"', publication)
        self.assertNotIn("NODE_OPTIONS", summary)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("actions/checkout", workflow)


if __name__ == "__main__":
    unittest.main()
