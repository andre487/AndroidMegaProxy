#!/usr/bin/env python3
"""Choose a PR and launch its tests using the GitHub API and Python's stdlib."""

import argparse
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = "andre487/AndroidMegaProxy"
MODES = [
    ("single", "Selectel UI: one Galaxy A14 (Android 15, paid rental)"),
    ("all", "Selectel UI: Android 11, 13, 15, 16, 17 (five paid rentals)"),
    ("ci", "Re-run CI: native Go + Android/JVM/lint/build (no device rental)"),
]


def github_token():
    for name in ("GH_TOKEN", "GITHUB_TOKEN"):
        if os.environ.get(name, "").strip():
            return os.environ[name].strip()
    if shutil.which("gh"):
        result = subprocess.run(
            ["gh", "auth", "token", "--hostname", "github.com"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    token = getpass.getpass("GitHub token (not saved): ").strip()
    if not token:
        raise RuntimeError(
            "A GitHub token is required (Actions: write, Pull requests: read)."
        )
    return token


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise RuntimeError("Repository must be OWNER/REPO.")
        self.repository = repository
        self.token = token

    def request(self, method, path, body=None):
        request = urllib.request.Request(
            "https://api.github.com/repos/" + self.repository + path,
            method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "MegaProxy-Actions",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            # Do not echo server responses, headers, or the token.
            hints = {
                401: "Check GitHub authentication.",
                403: "Check Actions write permission and rate limits.",
                404: "The PR/workflow may be unavailable to this token.",
                422: "Check workflow_dispatch support on the selected branch or rerun eligibility.",
            }
            suffix = (
                " Launch outcome is unknown; check Actions before retrying."
                if method == "POST" and error.code >= 500
                else ""
            )
            raise RuntimeError(
                f'GitHub HTTP {error.code}. {hints.get(error.code, "Request failed.")}'
                + suffix
            ) from None
        except (urllib.error.URLError, TimeoutError):
            suffix = (
                " Launch outcome is unknown; check Actions before retrying."
                if method == "POST"
                else ""
            )
            raise RuntimeError("GitHub network request failed." + suffix) from None

    def open_prs(self):
        result = []
        page = 1
        while True:
            items = self.request("GET", f"/pulls?state=open&per_page=100&page={page}")
            result.extend(items)
            if len(items) < 100:
                return result
            page += 1


def same_repository(pr, repository):
    return (pr["head"].get("repo") or {}).get(
        "full_name", ""
    ).lower() == repository.lower()


def choose(title, options):
    print("\n" + title)
    for index, (_, label) in enumerate(options, 1):
        # PR titles and branch names are untrusted terminal text.
        print(f"  {index}. " + "".join(c if c.isprintable() else " " for c in label))
    while True:
        value = input("Choose a number (q to cancel): ").strip()
        if value.lower() == "q":
            raise KeyboardInterrupt
        if value.isdecimal() and 1 <= int(value) <= len(options):
            return options[int(value) - 1][0]
        print("Enter a number from the list.")


def plan_run(client, pr, mode):
    if pr["state"] != "open" or not same_repository(pr, client.repository):
        raise RuntimeError(
            "Select an open PR from a branch in this repository; forks are unsupported."
        )
    if mode in ("single", "all"):
        return (
            "/actions/workflows/selectel-ui.yml/dispatches",
            {"ref": pr["head"]["ref"], "inputs": {"devices": mode}},
            f"https://github.com/{client.repository}/actions/workflows/selectel-ui.yml",
        )
    if mode != "ci":
        raise RuntimeError("Unknown test selection.")
    query = urllib.parse.urlencode(
        {"head_sha": pr["head"]["sha"], "event": "pull_request", "per_page": 100}
    )
    runs = client.request("GET", "/actions/workflows/ci.yml/runs?" + query)[
        "workflow_runs"
    ]
    runs = [
        run
        for run in runs
        if run["head_sha"] == pr["head"]["sha"]
        and run["head_branch"] == pr["head"]["ref"]
        and (run.get("head_repository") or {}).get("full_name", "").lower()
        == client.repository.lower()
    ]
    if not runs:
        raise RuntimeError(
            "No CI run exists for this PR commit yet. CI starts automatically on pushes."
        )
    run = max(runs, key=lambda item: item["id"])
    if run["status"] != "completed":
        raise RuntimeError("CI is already queued or running: " + run["html_url"])
    return f'/actions/runs/{run["id"]}/rerun', {}, run["html_url"]


def launch(client, pr, mode, dry_run=False):
    path, body, url = plan_run(client, pr, mode)
    print(f'\nPR #{pr["number"]}, commit {pr["head"]["sha"][:12]}')
    print(json.dumps(body, ensure_ascii=True))
    if dry_run:
        print("Dry run: no workflow started.\n" + url)
        return
    if input("Start this run? [y/N]: ").strip().lower() != "y":
        print("Cancelled.")
        return
    current = client.request("GET", f'/pulls/{pr["number"]}')
    if (
        current["state"] != "open"
        or not same_repository(current, client.repository)
        or current["head"]["sha"] != pr["head"]["sha"]
        or current["head"]["ref"] != pr["head"]["ref"]
    ):
        raise RuntimeError(
            "The PR changed while choosing. Start again to review its current commit."
        )
    # Dispatch uses a branch, so GitHub resolves its head at acceptance time. Never retry POST.
    response = client.request("POST", path, body)
    print("Launch accepted.\n" + ((response or {}).get("html_url") or url))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=REPOSITORY, help="GitHub OWNER/REPO")
    parser.add_argument(
        "--dry-run", action="store_true", help="Preview without starting workflows"
    )
    args = parser.parse_args()
    try:
        client = GitHub(args.repo, github_token())
        prs = [pr for pr in client.open_prs() if same_repository(pr, args.repo)]
        if not prs:
            print("No open PRs from repository branches.")
            return 0
        number = choose(
            "Open pull requests",
            [(pr["number"], f'#{pr["number"]} {pr["title"]}') for pr in prs],
        )
        pr = client.request("GET", f"/pulls/{number}")
        mode = choose("Tests to run", MODES)
        launch(client, pr, mode, args.dry_run)
        return 0
    except (KeyboardInterrupt, EOFError):
        print("\nCancelled.")
        return 130
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(
            (
                str(error)
                if isinstance(error, RuntimeError)
                else "GitHub CLI authentication timed out."
            ),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    sys.exit(main())
