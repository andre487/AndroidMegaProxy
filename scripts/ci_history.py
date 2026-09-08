"""Find successful ancestor checks through gh; never treat skipped jobs as coverage."""

import json
import subprocess
import urllib.parse

CHECKS = {
    "android": "Android tests and checks",
    "native": "Native Go tests",
    "python": "Python tests and style",
}
BASE_STEP = "Comparison base: "


def github_json(endpoint):
    result = subprocess.run(
        ["gh", "api", endpoint], capture_output=True, text=True, timeout=30
    )
    if result.returncode:
        raise RuntimeError("GitHub check history is unavailable")
    return json.loads(result.stdout)


def is_ancestor(base, head):
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base, head],
        capture_output=True,
        timeout=15,
    )
    return result.returncode == 0


def successful_baselines(
    repository,
    branch,
    pr_number,
    base,
    head,
    run_id,
    read=github_json,
    ancestor=is_ancestor,
):
    query = urllib.parse.urlencode(
        {
            "branch": branch,
            "event": "pull_request",
            "status": "completed",
            "per_page": 30,
        }
    )
    runs = read(f"repos/{repository}/actions/workflows/ci.yml/runs?{query}")[
        "workflow_runs"
    ]
    baselines = {}
    for run in sorted(runs, key=lambda run: run["id"], reverse=True):
        sha = run["head_sha"]
        # Exclude this run (including reruns) and later runs, forks, other PRs and rebased history.
        if (
            run["id"] >= run_id
            or run["head_branch"] != branch
            or not any(
                pr["number"] == pr_number
                and pr["head"]["repo"]["id"] == pr["base"]["repo"]["id"]
                for pr in run.get("pull_requests", [])
            )
            or not ancestor(sha, head)
        ):
            continue
        jobs = read(
            f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100"
        )["jobs"]
        # API PR references can change; this successful step records the actual event base.
        if not any(
            job["name"] == "Change scope"
            and job["conclusion"] == "success"
            and any(
                step["name"] == BASE_STEP + base and step["conclusion"] == "success"
                for step in job.get("steps", [])
            )
            for job in jobs
        ):
            continue
        for suite, name in CHECKS.items():
            matching = [job for job in jobs if job["name"] == name]
            if (
                suite not in baselines
                and len(matching) == 1
                and matching[0]["conclusion"] == "success"
            ):
                baselines[suite] = {"sha": sha, "run_id": run["id"]}
        if len(baselines) == len(CHECKS):
            break
    return baselines
