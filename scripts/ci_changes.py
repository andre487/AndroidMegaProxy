#!/usr/bin/env python3
"""Classify the full PR diff for CI; unknown paths conservatively run every suite."""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

SUITES = ("android", "native", "python")


def classify(paths):
    selected = set()
    for path in paths:
        # Markdown packaged in the app is runtime content, unlike repository docs.
        if path.startswith(("app/src/main/assets/", "app/src/main/res/")):
            selected.add("android")
        elif (
            PurePosixPath(path).suffix.lower() == ".md"
            or path.startswith(("docs/", "fastlane/metadata/", ".vscode/"))
            or path in ("LICENSE", ".gitignore", ".gitattributes")
        ):
            continue
        elif path.startswith((".github/", "fastlane/")) or path in (
            "Gemfile",
            "Gemfile.lock",
            ".ruby-version",
        ):
            selected.update(SUITES)
        elif path.startswith("native/"):
            selected.add("native")
            if not path.endswith("_test.go"):
                selected.add("android")
        elif path.endswith(".py") or path in (
            "pyproject.toml",
            "requirements-dev.txt",
        ):
            selected.add("python")
        elif (
            path.startswith(("app/", "gradle/"))
            or path.endswith((".gradle", ".gradle.kts"))
            or path in ("gradlew", "gradlew.bat", "gradle.properties")
        ):
            selected.add("android")
        elif path.startswith("scripts/") and path.endswith(".sh"):
            selected.add("android")
        else:
            selected.update(SUITES)
    return {suite: suite in selected for suite in SUITES}


def changed_files(base, head, pull_request=True):
    if not all(re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", sha) for sha in (base, head)):
        raise RuntimeError("Expected full base and head commit SHAs")
    if not base.strip("0"):
        return None  # Initial push has no reliable comparison point: run everything.
    commits = [f"{base}...{head}"] if pull_request else [base, head]
    try:
        output = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "-z", *commits, "--"],
            capture_output=True,
            check=True,
            timeout=60,
        ).stdout
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise RuntimeError(
            "Could not compute the full diff; CI scope must not be skipped"
        ) from None
    # --no-renames includes both paths when a code file moves into a docs-only directory.
    return [
        name.decode("utf-8", errors="surrogateescape")
        for name in output.split(b"\0")
        if name
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument(
        "--push",
        action="store_true",
        help="Compare push endpoints instead of the PR merge base",
    )
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        paths = changed_files(args.base, args.head, not args.push)
        result = classify(paths) if paths is not None else dict.fromkeys(SUITES, True)
        print(
            json.dumps(
                {**result, "changed_files": len(paths) if paths is not None else None}
            )
        )
        if args.github_output:
            with args.github_output.open("a") as output:
                for suite, enabled in result.items():
                    output.write(f"{suite}={str(enabled).lower()}\n")
        if args.summary:
            with args.summary.open("a") as summary:
                summary.write(
                    "## CI change scope\n\nCompared the full PR diff"
                    if not args.push
                    else "## CI change scope\n\nCompared the push endpoints"
                )
                summary.write(f" (`{args.base[:12]}` → `{args.head[:12]}`).\n\n")
                for suite, enabled in result.items():
                    summary.write(
                        f"- {suite}: {'run' if enabled else 'skip — no relevant changes'}\n"
                    )
        return 0
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
