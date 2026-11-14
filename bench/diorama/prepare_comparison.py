#!/usr/bin/env python3
"""Prepare an unchanged reference baker beside a patched Diorama implementation."""
import argparse
from pathlib import Path
import re
import subprocess

REVISION = "7aa1484cd5dfe4ec19d246a58fb23bbc48a8bc6e"
SOURCE = "src/main/java/dev/diorama/bake/TileBaker.java"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--patch", type=Path)
    parser.add_argument("--remove-reference", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if git(root, "rev-parse", "HEAD").strip() != REVISION:
        parser.error(f"This comparison targets Diorama commit {REVISION}")
    reference = root / SOURCE.replace("TileBaker.java", "TileBakerBaseline.java")
    original = git(root, "show", f"HEAD:{SOURCE}")
    expected = re.sub(r"\bTileBaker\b", "TileBakerBaseline", original)
    if args.remove_reference:
        if args.patch:
            parser.error("--remove-reference does not apply patches")
        if reference.exists():
            if reference.read_text() != expected:
                parser.error("Reference has been edited; refusing to remove it")
            reference.unlink()
        print("Reference source removed; candidate changes retained.")
        return
    if not args.patch:
        parser.error("Supply --patch for the optimization being compared")
    if reference.exists() or git(root, "status", "--porcelain", "--untracked-files=no").strip():
        parser.error("Use a clean checkout without an existing reference source")
    patch = args.patch.resolve()
    git(root, "apply", "--check", str(patch))
    reference.write_text(expected)
    try:
        git(root, "apply", str(patch))
    except Exception:
        reference.unlink()
        raise
    print("Reference and candidate prepared. Build/launch through modaccel, then run compare.java.")


if __name__ == "__main__":
    main()
