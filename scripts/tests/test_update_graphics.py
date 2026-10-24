import hashlib
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import update_graphics as m

ICON = b"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120">
<g clip-path="url(#clip)"><g id="icon-2"><g id="Background"><rect width="120" height="120" fill="blue"/></g>
<path id="logo" d="M30 50H90V70H30Z" fill="white"/></g></g></svg>"""


class UpdateGraphicsTest(unittest.TestCase):
    def github(self, path):
        self.calls.append(path)
        if path == "commits/main":
            return json.dumps({"sha": "a" * 40}).encode()
        if "contents/LICENSE?" in path:
            return b"license\n"
        if "social-preview.svg" in path:
            return ICON.replace(b"120 120", b"1280 640")
        return ICON

    def test_pins_all_sources_and_renders_before_replacing_files(self):
        for fail in (False, True):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                badge = root / m.BADGE
                badge.parent.mkdir(parents=True)
                badge.write_bytes(ICON)
                target = root / "docs/assets/social-preview.png"
                target.write_bytes(b"existing")
                retired = root / "assets/branding/megaproxy-launcher-master.png"
                retired.parent.mkdir(parents=True)
                retired.write_bytes(b"original")
                self.calls = []
                sizes = []

                def render(content, width, height, directory):
                    sizes.append((width, height))
                    if fail and len(sizes) == 3:
                        raise RuntimeError("render failed")
                    return b"PNG" + struct.pack(">II", width, height) + content

                with (
                    patch.object(m, "github", self.github),
                    patch.object(m.shutil, "which", return_value="rsvg-convert"),
                    patch.object(
                        m.subprocess,
                        "run",
                        return_value=SimpleNamespace(stdout="rsvg-convert test\n"),
                    ),
                    patch.object(m, "render", render),
                ):
                    if fail:
                        with self.assertRaises(RuntimeError):
                            m.update("main", root)
                        self.assertEqual(b"existing", target.read_bytes())
                        self.assertTrue(retired.exists())
                        self.assertFalse((root / m.LOCK).exists())
                        continue
                    m.update("main", root)
                lock = json.loads((root / m.LOCK).read_text())
                self.assertEqual("a" * 40, lock["commit"])
                self.assertEqual("commits/main", self.calls[0])
                self.assertTrue(
                    all(path.endswith("?ref=" + "a" * 40) for path in self.calls[1:])
                )
                self.assertEqual(len(m.SOURCES) + 1, len(self.calls))
                self.assertEqual(
                    b"PNG"
                    + struct.pack(">II", 1280, 640)
                    + ICON.replace(b"120 120", b"1280 640"),
                    target.read_bytes(),
                )
                self.assertIn("SVG/social-preview.svg", lock["sources"])
                self.assertIn((1280, 640), sizes)
                self.assertIn((432, 432), sizes)
                self.assertFalse(retired.exists())
                for path, checksum in lock["outputs"].items():
                    self.assertEqual(checksum, m.digest((root / path).read_bytes()))

    def test_adaptive_layers_preserve_center_and_reject_changed_structure(self):
        background, foreground = map(m.svg, m.adaptive_layers(ICON))
        self.assertIsNone(background.find(f".//*[@id='logo']"))
        self.assertIsNone(foreground.find(f".//*[@id='Background']"))
        self.assertIsNotNone(foreground.find(f".//*[@id='logo']"))
        self.assertEqual("-20 -20 160 160", foreground.get("viewBox"))
        with self.assertRaises(ValueError):
            m.adaptive_layers(ICON.replace(b'id="Background"', b'id="renamed"'))
        with self.assertRaises(ValueError):
            m.svg(
                b'<svg xmlns="http://www.w3.org/2000/svg"><image href="logo.png"/></svg>'
            )

    def test_committed_artwork_has_exact_recorded_bytes_and_no_extra_masters(self):
        lock = json.loads((m.ROOT / m.LOCK).read_text())
        self.assertRegex(lock["commit"], r"^[a-f0-9]{40}$")
        for path, checksum in (lock["outputs"] | lock["local_sources"]).items():
            self.assertEqual(
                checksum, hashlib.sha256((m.ROOT / path).read_bytes()).hexdigest(), path
            )
        artwork = {
            str(path.relative_to(m.ROOT))
            for folder in (
                "assets",
                "docs/assets",
                "fastlane/metadata",
                "app/src/main/res",
            )
            for path in (m.ROOT / folder).rglob("*")
            if path.suffix.lower() in (".png", ".svg", ".webp", ".jpg", ".jpeg", ".ico")
            and "phoneScreenshots" not in path.parts
        }
        self.assertEqual(
            artwork,
            {path for path in lock["outputs"] if path.endswith(".png")}
            | set(lock["local_sources"]),
        )
        preview = (m.ROOT / "docs/assets/social-preview.png").read_bytes()
        self.assertEqual((1280, 640), struct.unpack(">II", preview[16:24]))
        for density, scale in m.DENSITIES.items():
            for name, size in (
                ("ic_launcher", 48),
                ("ic_launcher_round", 48),
                ("ic_launcher_foreground", 108),
                ("ic_launcher_background", 108),
            ):
                data = (
                    m.ROOT / f"app/src/main/res/mipmap-{density}/{name}.png"
                ).read_bytes()
                self.assertEqual(
                    (int(size * scale),) * 2, struct.unpack(">II", data[16:24])
                )
            data = (
                m.ROOT / f"app/src/main/res/drawable-{density}/ic_vpn_notification.png"
            ).read_bytes()
            self.assertEqual((int(24 * scale),) * 2, struct.unpack(">II", data[16:24]))
