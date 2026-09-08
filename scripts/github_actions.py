#!/usr/bin/env python3
"""Choose a PR and launch its tests through the authenticated GitHub CLI."""

import argparse
import json
import os
import re
import shlex
import subprocess
import sys

REPOSITORY = "andre487/AndroidMegaProxy"
MODES = [
    ("ci", "Re-run all CI jobs, including skipped checks"),
    ("failed", "Re-run failed CI jobs only"),
]
PR_FIELDS = "number,title,state,headRefName,headRefOid,isCrossRepository"


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise RuntimeError("Repository must be OWNER/REPO.")
        self.repository = repository

    def argv(self, *args):
        return ["gh", *args, "--repo", self.repository]

    def run(self, *args):
        mutating = args[:2] == ("run", "rerun")
        uncertain = (
            " Check Actions before retrying; the launch may have been accepted."
            if mutating
            else ""
        )
        env = os.environ.copy()
        env.pop("GH_DEBUG", None)
        try:
            result = subprocess.run(
                self.argv(*args), capture_output=True, text=True, timeout=60, env=env
            )
        except FileNotFoundError:
            raise RuntimeError(
                "GitHub CLI is required. Install gh and run: gh auth login"
            ) from None
        except subprocess.TimeoutExpired:
            raise RuntimeError("GitHub CLI timed out." + uncertain) from None
        if result.returncode:
            # Authentication is owned by gh; never echo tokens from its diagnostics.
            diagnostic = result.stderr.strip()
            for name in ("GH_TOKEN", "GITHUB_TOKEN"):
                if env.get(name):
                    diagnostic = diagnostic.replace(env[name], "<redacted>")
            diagnostic = "".join(c if c.isprintable() else " " for c in diagnostic)[
                :500
            ]
            raise RuntimeError(f"GitHub CLI failed: {diagnostic}" + uncertain)
        return result.stdout.strip()

    def read_json(self, *args):
        try:
            return json.loads(self.run(*args))
        except json.JSONDecodeError:
            raise RuntimeError(
                "GitHub CLI returned invalid JSON. Update gh and retry."
            ) from None

    def open_prs(self):
        return self.read_json(
            "pr", "list", "--state", "open", "--limit", "1000", "--json", PR_FIELDS
        )

    def pr(self, number):
        return self.read_json("pr", "view", str(number), "--json", PR_FIELDS)


def choose(title, options):
    print("\n" + title)
    for index, (_, label) in enumerate(options, 1):
        print(f"  {index}. " + "".join(c if c.isprintable() else " " for c in label))
    while True:
        value = input("Choose a number (q to cancel): ").strip()
        if value.lower() == "q":
            raise KeyboardInterrupt
        if value.isdecimal() and 1 <= int(value) <= len(options):
            return options[int(value) - 1][0]
        print("Enter a number from the list.")


def plan_run(client, pr, mode):
    if pr["state"] != "OPEN" or pr["isCrossRepository"]:
        raise RuntimeError(
            "Select an open PR from this repository; forks are unsupported."
        )
    if mode not in ("ci", "failed"):
        raise RuntimeError("Unknown test selection.")
    runs = client.read_json(
        "run",
        "list",
        "--workflow",
        "ci.yml",
        "--commit",
        pr["headRefOid"],
        "--branch",
        pr["headRefName"],
        "--event",
        "pull_request",
        "--limit",
        "1",
        "--json",
        "databaseId,status,conclusion,url,headSha,headBranch",
    )
    if (
        not runs
        or runs[0]["headSha"] != pr["headRefOid"]
        or runs[0]["headBranch"] != pr["headRefName"]
    ):
        raise RuntimeError(
            "No CI run exists for this PR commit yet. CI starts automatically on pushes."
        )
    run = runs[0]
    if run["status"] != "completed":
        raise RuntimeError("CI is already queued or running: " + run["url"])
    command = ["run", "rerun", str(run["databaseId"])]
    if mode == "failed":
        if run["conclusion"] != "failure":
            raise RuntimeError(
                "This CI run has no failed conclusion; choose all jobs instead."
            )
        command.append("--failed")
    return command, run["url"]


def launch(client, pr, mode, dry_run=False, yes=False):
    command, url = plan_run(client, pr, mode)
    print(f'\nPR #{pr["number"]}, commit {pr["headRefOid"][:12]}')
    print(shlex.join(client.argv(*command)))
    if dry_run:
        print("Dry run: no workflow started.\n" + url)
        return
    if not yes and input("Start this run? [y/N]: ").strip().lower() != "y":
        print("Cancelled.")
        return
    current = client.pr(pr["number"])
    if (
        current["state"] != "OPEN"
        or current["isCrossRepository"]
        or current["headRefOid"] != pr["headRefOid"]
        or current["headRefName"] != pr["headRefName"]
    ):
        raise RuntimeError(
            "The PR changed while choosing. Start again to review its current commit."
        )
    # Rerun the selected immutable run ID; never retry an ambiguous launch.
    output = client.run(*command)
    print("Launch accepted.\n" + (output or url))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=REPOSITORY, help="GitHub OWNER/REPO")
    parser.add_argument(
        "--dry-run", action="store_true", help="Preview without starting workflows"
    )
    parser.add_argument(
        "--yes",
        "-y",
        action="store_true",
        help="Skip final launch confirmation; still choose a PR and CI action",
    )
    args = parser.parse_args()
    try:
        client = GitHub(args.repo)
        prs = [pr for pr in client.open_prs() if not pr["isCrossRepository"]]
        if not prs:
            print("No open PRs from repository branches.")
            return 0
        number = choose(
            "Open pull requests",
            [(pr["number"], f'#{pr["number"]} {pr["title"]}') for pr in prs],
        )
        pr = client.pr(number)
        mode = choose("Tests to run", MODES)
        launch(client, pr, mode, args.dry_run, args.yes)
        return 0
    except (KeyboardInterrupt, EOFError):
        print("\nCancelled.")
        return 130
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
