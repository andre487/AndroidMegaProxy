import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import renew_config_schema as m


class RenewConfigSchemaTest(unittest.TestCase):
    def test_pins_one_commit_and_records_exact_bytes(self):
        calls = []

        def github(path, raw=False):
            calls.append(path)
            if not raw:
                return json.dumps({"sha": "a" * 40}).encode()
            if ".schema.json" in path:
                return json.dumps(
                    {
                        "$schema": "https://json-schema.org/draft/2020-12/schema",
                        "properties": {"version": {"const": 8}},
                    }
                ).encode()
            return b"{}" if ".json" in path else b"license\n"

        with tempfile.TemporaryDirectory() as root, patch.object(m, "github", github):
            directory = Path(root)
            m.renew("main", directory)
            lock = json.loads((directory / "schema-lock.json").read_text())
            self.assertEqual("a" * 40, lock["commit"])
            for name, checksum in lock["files"].items():
                self.assertEqual(
                    hashlib.sha256((directory / name).read_bytes()).hexdigest(),
                    checksum,
                )
            self.assertEqual("commits/main", calls[0])
            self.assertTrue(
                all(path.endswith("?ref=" + "a" * 40) for path in calls[1:])
            )

    def test_failure_or_new_version_does_not_replace_existing_files(self):
        for response in (
            RuntimeError("download failed"),
            b'{"properties":{"version":{"const":9}}}',
        ):
            with tempfile.TemporaryDirectory() as root:
                directory = Path(root)
                target = directory / "android-v8.schema.json"
                target.write_text("existing")
                with patch.object(
                    m,
                    "github",
                    side_effect=response if isinstance(response, Exception) else None,
                    return_value=response,
                ):
                    with self.assertRaises((RuntimeError, ValueError)):
                        m.renew("b" * 40, directory)
                self.assertEqual("existing", target.read_text())
                self.assertEqual([target], list(directory.iterdir()))
