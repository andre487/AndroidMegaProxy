import os
import unittest
import unittest.mock

import click.testing

import manage


class ManageTests(unittest.TestCase):
    def test_android_checks_reject_signing_before_build(self):
        with (
            unittest.mock.patch.dict(os.environ, {"MEGAPROXY_KEY_PASSWORD": "example"}),
            unittest.mock.patch.object(manage, "android_build") as build,
        ):
            result = click.testing.CliRunner().invoke(manage.cli, ["android", "checks"])
        self.assertEqual(result.exit_code, 1)
        self.assertIn("MEGAPROXY_KEY_PASSWORD", result.output)
        self.assertNotIn("example", result.output)
        build.assert_not_called()

    def test_release_finish_uses_workflow_inputs(self):
        with unittest.mock.patch.object(manage, "script") as script:
            result = click.testing.CliRunner().invoke(
                manage.cli,
                ["release", "finish"],
                env={
                    "RELEASE_VERSION": "1.2.3",
                    "RELEASE_PR": "42",
                    "RELEASE_HEAD": "abc",
                },
            )
        self.assertEqual(result.exit_code, 0, result.output)
        script.assert_called_once_with(
            "release_automation.py",
            "finish",
            "--version",
            "1.2.3",
            "--pr",
            "42",
            "--head",
            "abc",
        )
