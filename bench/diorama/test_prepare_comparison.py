#!/usr/bin/env python3
"""Exercise independent baseline preparation and safe cleanup in a disposable repository."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("prepare_comparison", Path(__file__).with_name("prepare_comparison.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.source = self.root / prepare.DIRECTORY
        self.source.mkdir(parents=True)
        self.originals = {
            "TileBaker": "class TileBaker { VoxelReader r; CaveCut c; TileBaker() {} }\n",
            "VoxelReader": "class VoxelReader { VoxelReader() {} }\n",
            "CaveCut": "class CaveCut { CaveCut(VoxelReader r) {} }\n",
        }
        for name, content in self.originals.items():
            (self.source / f"{name}.java").write_text(content)
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        revision = self.git("rev-parse", "HEAD").strip()
        self.revision = mock.patch.object(prepare, "REVISION", revision)
        self.revision.start()
        self.addCleanup(self.revision.stop)
        (self.source / "VoxelReader.java").write_text(self.originals["VoxelReader"] + "// candidate\n")
        self.patch = Path(self.temp.name) / "candidate.patch"
        self.patch.write_text(self.git("diff"))
        self.git("checkout", "--", ".")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True)

    def run_main(self, *args):
        with mock.patch.object(sys, "argv", ["prepare", "--root", str(self.root), *args]):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                prepare.main()

    def test_independent_sources_and_safe_cleanup(self):
        self.run_main("--patch", str(self.patch))
        baker = self.source / "TileBakerBaseline.java"
        reader = self.source / "VoxelReaderBaseline.java"
        cave = self.source / "CaveCutBaseline.java"
        self.assertIn("VoxelReaderBaseline r; CaveCutBaseline c", baker.read_text())
        self.assertIn("VoxelReaderBaseline()", reader.read_text())
        self.assertIn("CaveCutBaseline(VoxelReaderBaseline r)", cave.read_text())
        self.assertNotIn("candidate", reader.read_text())
        self.assertIn("candidate", (self.source / "VoxelReader.java").read_text())
        cave.write_text(cave.read_text() + "// edited\n")
        with self.assertRaises(SystemExit):
            self.run_main("--remove-reference")
        self.assertTrue(all(path.exists() for path in (baker, reader, cave)))
        cave.write_text(cave.read_text().removesuffix("// edited\n"))
        self.run_main("--remove-reference")
        self.assertTrue(all(not path.exists() for path in (baker, reader, cave)))
        self.assertIn("candidate", (self.source / "VoxelReader.java").read_text())

    def test_legacy_reference_cleanup(self):
        baker = self.source / "TileBakerBaseline.java"
        baker.write_text(self.originals["TileBaker"].replace("TileBaker", "TileBakerBaseline"))
        self.run_main("--remove-reference")
        self.assertFalse(baker.exists())

    def test_wrong_revision_refuses(self):
        with mock.patch.object(prepare, "REVISION", "0" * 40):
            with self.assertRaises(SystemExit):
                self.run_main("--patch", str(self.patch))
        self.assertFalse((self.source / "TileBakerBaseline.java").exists())


if __name__ == "__main__":
    unittest.main()
