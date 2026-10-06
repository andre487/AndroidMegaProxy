#!/usr/bin/env python3
"""Vendor reviewed MegaProxyConfig schemas from an immutable GitHub revision."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

REPOSITORY = "andre487/MegaProxyConfig"
FILES = (
    "schemas/android-v8.schema.json",
    "schemas/megaproxy-v8.schema.json",
    "examples/android-v8.json",
    "examples/browser-v8.json",
    "LICENSE",
)
DIRECTORY = Path(__file__).resolve().parents[1] / "config-schema"


def github(path, raw=False):
    args = ["gh", "api", f"repos/{REPOSITORY}/{path}"]
    if raw:
        args += ["-H", "Accept: application/vnd.github.raw+json"]
    return subprocess.run(args, check=True, capture_output=True, timeout=60).stdout


def renew(ref="main", directory=DIRECTORY):
    commit = (
        ref
        if re.fullmatch(r"[a-f0-9]{40}", ref)
        else json.loads(github(f"commits/{ref}"))["sha"]
    )
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("GitHub returned an invalid commit")
    contents = {
        Path(path).name: github(f"contents/{path}?ref={commit}", raw=True)
        for path in FILES
    }
    # Fetch and inspect everything before replacing any committed file.
    for name, content in contents.items():
        if name.endswith(".json"):
            document = json.loads(content)
            if name.endswith(".schema.json"):
                if (
                    document.get("$schema")
                    != "https://json-schema.org/draft/2020-12/schema"
                    or document.get("properties", {}).get("version", {}).get("const")
                    != 8
                ):
                    raise ValueError(
                        "Unexpected schema version; update consumers explicitly"
                    )
    lock = {
        "repository": f"https://github.com/{REPOSITORY}",
        "commit": commit,
        "files": {
            name: hashlib.sha256(content).hexdigest()
            for name, content in contents.items()
        },
    }
    contents["schema-lock.json"] = (json.dumps(lock, indent=2) + "\n").encode()
    directory.mkdir(parents=True, exist_ok=True)
    for name, content in contents.items():
        temporary = directory / f"{name}.tmp"
        temporary.write_bytes(content)
        temporary.replace(directory / name)
    print(f"Updated MegaProxyConfig to {commit}. Review and commit config-schema/.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="main")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.ref):
        parser.error("Expected a commit or branch name")
    renew(args.ref)


if __name__ == "__main__":
    main()
