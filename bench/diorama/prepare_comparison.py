#!/usr/bin/env python3
"""Prepare independent pinned reference classes beside a patched Diorama implementation."""
import argparse
from pathlib import Path
import re
import subprocess

REVISION = "7aa1484cd5dfe4ec19d246a58fb23bbc48a8bc6e"
DIRECTORY = "src/main/java/dev/diorama/bake"
CLASSES = ("TileBaker", "VoxelReader", "CaveCut")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True)


def reference_sources(root):
    """Use only the pinned commit, consistently renaming references and constructors."""
    originals = {}
    expected = {}
    for name in CLASSES:
        original = git(root, "show", f"{REVISION}:{DIRECTORY}/{name}.java")
        originals[name] = original
        expected[root / DIRECTORY / f"{name}Baseline.java"] = re.sub(
            r"\b(?:TileBaker|VoxelReader|CaveCut)\b",
            lambda match: match.group() + "Baseline",
            original,
        )
    return originals, expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--patch", type=Path)
    parser.add_argument("--remove-reference", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if git(root, "rev-parse", "HEAD").strip() != REVISION:
        parser.error(f"This comparison targets Diorama commit {REVISION}")
    originals, expected = reference_sources(root)
    if args.remove_reference:
        if args.patch:
            parser.error("--remove-reference does not apply patches")
        # Older preparations contained only TileBakerBaseline and shared its reader.
        legacy = re.sub(r"\bTileBaker\b", "TileBakerBaseline", originals["TileBaker"])
        baker = root / DIRECTORY / "TileBakerBaseline.java"
        present = [path for path in expected if path.exists()]
        for path in present:
            content = path.read_text()
            if content != expected[path] and not (path == baker and content == legacy):
                parser.error(f"Reference has been edited; refusing to remove any: {path}")
        for path in present:
            path.unlink()
        print("Reference sources removed; candidate changes retained.")
        return
    if not args.patch:
        parser.error("Supply --patch for the optimization being compared")
    if any(path.exists() for path in expected) or git(root, "status", "--porcelain", "--untracked-files=no").strip():
        parser.error("Use a clean checkout without existing reference sources")
    patch = args.patch.resolve()
    git(root, "apply", "--check", str(patch))
    written = []
    try:
        for path, content in expected.items():
            path.write_text(content)
            written.append(path)
        git(root, "apply", str(patch))
    except Exception:
        for path in written:
            path.unlink()
        raise
    print("Reference and candidate prepared. Build/launch through modaccel, then run compare.java.")


if __name__ == "__main__":
    main()
