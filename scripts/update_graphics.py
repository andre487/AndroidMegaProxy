#!/usr/bin/env python3
"""Generate committed Android/store/docs artwork from pinned MegaProxyIdentity SVGs."""

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "andre487/MegaProxyIdentity"
LOCK = "assets/branding/identity-lock.json"
BADGE = "docs/assets/get-it-on-github.svg"
SOURCES = (
    "SVG/icon.svg",
    "SVG/icon-radius.svg",
    "SVG/icon-round.svg",
    "SVG/feature-graphic.svg",
    "SVG/icon-taskbar.svg",
    "LICENSE",
)
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")


def digest(content):
    return hashlib.sha256(content).hexdigest()


def github(path):
    return subprocess.run(
        [
            "gh",
            "api",
            f"repos/{REPOSITORY}/{path}",
            "-H",
            "Accept: application/vnd.github.raw+json",
        ],
        check=True,
        capture_output=True,
        timeout=60,
    ).stdout


def svg(content):
    root = ET.fromstring(content)
    if root.tag != f"{{{SVG}}}svg":
        raise ValueError("Expected an SVG document")
    for node in root.iter():
        if node.tag == f"{{{SVG}}}image":
            raise ValueError("Embedded raster artwork is not a vector source")
        for key, value in node.attrib.items():
            if key.rsplit("}", 1)[-1] == "href" and not value.startswith("#"):
                raise ValueError("SVG sources must be self-contained")
    return root


def adaptive_layers(content):
    background, foreground = svg(content), svg(content)
    for root, keep_background in ((background, True), (foreground, False)):
        artwork = root.find(f"{{{SVG}}}g[@id='icon-2']")
        if artwork is None or artwork.find(f"{{{SVG}}}g[@id='Background']") is None:
            raise ValueError(
                "Identity icon layers changed; review the adaptive icon recipe"
            )
        for child in list(artwork):
            if (child.get("id") == "Background") != keep_background:
                artwork.remove(child)
    # Keep the complete wordmark inside Android's 66dp safe circle on the 108dp layer.
    x, y, width, height = map(float, foreground.get("viewBox").split())
    foreground.set(
        "viewBox",
        f"{x - width / 6:g} {y - height / 6:g} {width / .75:g} {height / .75:g}",
    )
    return ET.tostring(background), ET.tostring(foreground)


def social_preview(content):
    artwork = svg(content)
    artwork.set("width", "1280")
    artwork.set("height", "640")
    artwork.set("preserveAspectRatio", "xMidYMid slice")
    canvas = ET.Element(f"{{{SVG}}}svg", {"viewBox": "0 0 1280 640"})
    canvas.append(artwork)
    return ET.tostring(canvas)


def render(content, width, height, directory):
    source, output = directory / "source.svg", directory / "render.png"
    source.write_bytes(content)
    subprocess.run(
        [
            "rsvg-convert",
            "--width",
            str(width),
            "--height",
            str(height),
            "--output",
            str(output),
            str(source),
        ],
        check=True,
        capture_output=True,
        timeout=60,
    )
    data = output.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (
        width,
        height,
    ):
        raise ValueError("Renderer returned an unexpected PNG size")
    return data


def update(ref="main", root=ROOT):
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", ref):
        raise ValueError("Expected an Identity commit or branch name")
    if not shutil.which("rsvg-convert"):
        raise RuntimeError("Install librsvg (rsvg-convert) before updating graphics")
    commit = (
        ref
        if re.fullmatch(r"[a-f0-9]{40}", ref)
        else json.loads(github(f"commits/{ref}"))["sha"]
    )
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("GitHub returned an invalid commit")
    sources = {name: github(f"contents/{name}?ref={commit}") for name in SOURCES}
    local_badge = (root / BADGE).read_bytes()
    for name, content in sources.items():
        if name.endswith(".svg"):
            svg(content)
    svg(local_badge)
    background, foreground = adaptive_layers(sources["SVG/icon.svg"])
    outputs = {"assets/branding/IDENTITY-LICENSE": sources["LICENSE"]}
    with tempfile.TemporaryDirectory(prefix="megaproxy-graphics-") as temporary:
        directory = Path(temporary)

        def png(path, content, width, height):
            outputs[path] = render(content, width, height, directory)

        for density, scale in DENSITIES.items():
            folder = f"app/src/main/res/mipmap-{density}"
            for name, source in (
                ("ic_launcher", "icon-radius"),
                ("ic_launcher_round", "icon-round"),
            ):
                size = int(48 * scale)
                png(f"{folder}/{name}.png", sources[f"SVG/{source}.svg"], size, size)
            size = int(108 * scale)
            png(f"{folder}/ic_launcher_background.png", background, size, size)
            png(f"{folder}/ic_launcher_foreground.png", foreground, size, size)
            size = int(24 * scale)
            png(
                f"app/src/main/res/drawable-{density}/ic_vpn_notification.png",
                sources["SVG/icon-taskbar.svg"],
                size,
                size,
            )
        icon = render(sources["SVG/icon.svg"], 512, 512, directory)
        feature = render(sources["SVG/feature-graphic.svg"], 1024, 500, directory)
        for language in ("en-US", "ru-RU"):
            outputs[f"fastlane/metadata/android/{language}/images/icon.png"] = icon
            outputs[
                f"fastlane/metadata/android/{language}/images/featureGraphic.png"
            ] = feature
        png(
            "docs/assets/social-preview.png",
            social_preview(sources["SVG/feature-graphic.svg"]),
            1280,
            640,
        )
        png("docs/assets/get-it-on-github.png", local_badge, 1292, 500)
    adaptive = b"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/update_graphics.py; edit MegaProxyIdentity SVG sources. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher", "ic_launcher_round"):
        outputs[f"app/src/main/res/mipmap-anydpi-v26/{name}.xml"] = adaptive
    version = subprocess.run(
        ["rsvg-convert", "--version"],
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    ).stdout.strip()
    lock = {
        "repository": f"https://github.com/{REPOSITORY}",
        "commit": commit,
        "renderer": version,
        "sources": {name: digest(content) for name, content in sources.items()},
        "local_sources": {BADGE: digest(local_badge)},
        "outputs": {name: digest(content) for name, content in sorted(outputs.items())},
    }
    outputs[LOCK] = (json.dumps(lock, indent=2) + "\n").encode()
    # Complete every download/render before replacing any committed asset.
    for name, content in outputs.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        temporary.write_bytes(content)
        temporary.replace(target)
    for obsolete in (
        "assets/branding/megaproxy-launcher-master.png",
        "app/src/main/res/drawable/ic_vpn_notification.xml",
        "app/src/main/res/values/ic_launcher_colors.xml",
    ):
        (root / obsolete).unlink(missing_ok=True)
    print(
        f"Generated {len(outputs) - 1} assets from MegaProxyIdentity {commit}. Review and commit the outputs and {LOCK}."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="main")
    args = parser.parse_args()
    update(args.ref)


if __name__ == "__main__":
    main()
