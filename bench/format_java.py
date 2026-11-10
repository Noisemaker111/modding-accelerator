"""Format the Java under tools/, changing only files whose compiled bytecode stays the same.

    python bench/format_java.py            format what can be proven safe, and say what was left alone
    python bench/format_java.py --check    change nothing; exit 1 when a provable file is not formatted

The formatter is prettier with prettier-plugin-java (120 columns, 4 spaces), installed once into .runs/format
with npm, so this needs Node on the PATH as well as a JDK. Each file is formatted in a copy, then the original
and the copy are both compiled with no debug information and the class files compared. tools/cli compiles
as one program; every other file is tried alone against it. A file is written back only when its bytecode is
identical, so a formatting run cannot change what the code does.

Files that do not compile here are left alone: the code that runs inside the game or a server (it needs the
game's jars), the eval snippets under tools/tests/*/ (they are not whole classes), and the older Windows lane.
"""
import argparse
import filecmp
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NODE = ROOT / ".runs" / "format"
PRETTIER, PLUGIN = "prettier@3.3.3", "prettier-plugin-java@2.6.5"
LEGACY = {"LabAgent.java", "LabDaemon.java", "ApiDiff.java", "SymbolDiff.java"}   # the older Windows lane: don't add to it, don't reformat it


def sh(cmd, cwd=None):
    return subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")


def formatter():
    """The prettier command line, installing it on first use. Raises RuntimeError with what is missing."""
    npm, node = shutil.which("npm"), shutil.which("node")
    if not npm or not node:
        raise RuntimeError("format_java needs Node (node and npm on the PATH) to run prettier.")
    cli = NODE / "node_modules" / "prettier" / "bin" / "prettier.cjs"
    plugin = NODE / "node_modules" / "prettier-plugin-java" / "dist" / "index.js"
    if not cli.is_file() or not plugin.is_file():
        NODE.mkdir(parents=True, exist_ok=True)
        r = sh([npm, "install", "--no-save", "--no-audit", "--no-fund", "--prefix", NODE, PRETTIER, PLUGIN])
        if r.returncode or not cli.is_file() or not plugin.is_file():
            raise RuntimeError("npm could not install prettier: " + " ".join((r.stderr or r.stdout).split())[-300:])
    return [node, cli, "--plugin", plugin, "--print-width", "120", "--tab-width", "4", "--end-of-line", "lf", "--no-config", "--ignore-path", os.devnull]


def javac(files, out, classpath=None):
    out.mkdir(parents=True, exist_ok=True)
    cmd = ["javac", "-g:none", "-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", out] + (["-cp", classpath] if classpath else []) + files
    return sh(cmd).returncode == 0


def same_tree(a, b):
    d = filecmp.dircmp(a, b)
    if d.left_only or d.right_only or d.funny_files:
        return False
    if any(not filecmp.cmp(Path(a) / f, Path(b) / f, shallow=False) for f in d.common_files):
        return False
    return all(same_tree(Path(a) / s, Path(b) / s) for s in d.common_dirs)


def plan(work):
    """Format a copy of tools/ and sort its files: (to write back, already formatted, left alone with the reason)."""
    orig, fmt = work / "orig", work / "fmt"
    shutil.copytree(ROOT / "tools", orig / "tools")
    shutil.copytree(ROOT / "tools", fmt / "tools")
    targets = sorted(p for p in (fmt / "tools").rglob("*.java") if p.name not in LEGACY and "stubs" not in p.parts)
    # prettier-java does not always settle in one pass, so format until nothing changes (PackCheck.java took three).
    cmd = formatter()
    for _ in range(6):
        before = [p.read_bytes() for p in targets]
        sh(cmd + ["--write"] + targets)
        if before == [p.read_bytes() for p in targets]:
            break
    else:
        raise RuntimeError("prettier did not settle after 6 passes; nothing was changed.")
    rel = lambda p: p.relative_to(fmt).as_posix()

    def proven(name, files, cp_orig=None, cp_fmt=None):
        """True: same bytecode. False: it differs. None: the original does not compile here."""
        a, b = work / f"{name}-a", work / f"{name}-b"
        if not javac([orig / f for f in files], a, cp_orig):
            return None
        return javac([fmt / f for f in files], b, cp_fmt) and same_tree(a, b)

    ok, left = [], []
    cli = [rel(p) for p in targets if p.parent.name == "cli" and p.name != "Bootstrap.java"]
    whole = proven("cli", cli)
    if whole:
        ok += cli
    else:
        left += [(f, "tools/cli does not compile here" if whole is None else "tools/cli compiles to different bytecode once formatted") for f in cli]
    for n, p in enumerate(t for t in targets if rel(t) not in cli):
        res = proven(f"one{n}", [rel(p)], work / "cli-a", work / "cli-b")
        if res:
            ok.append(rel(p))
        else:
            left.append((rel(p), "does not compile here, so its bytecode cannot be compared" if res is None else "compiles to DIFFERENT bytecode once formatted"))
    change = [f for f in ok if (orig / f).read_bytes() != (fmt / f).read_bytes()]
    return fmt, change, [f for f in ok if f not in change], left


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="write nothing; exit 1 when a provable file is not formatted")
    ap.add_argument("--quiet", action="store_true", help="do not list the files left alone")
    args = ap.parse_args(argv)
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        try:
            fmt, change, done, left = plan(Path(tmp))
        except RuntimeError as e:
            print(f"format_java: {e}", file=sys.stderr)
            return 2
        if not args.check:
            for f in change:
                shutil.copyfile(fmt / f, ROOT / f)
    verb = "need formatting" if args.check else "formatted, bytecode identical"
    print(f"{len(change)} files {verb}; {len(done)} already formatted; {len(left)} left alone.")
    for f in change:
        print(f"  {'not formatted' if args.check else 'formatted'}: {f}")
    if not args.quiet:
        for f, why in left:
            print(f"  left alone: {f}: {why}")
    differs = [f for f, why in left if "DIFFERENT" in why or "different" in why]
    if differs:
        print("A formatted file compiled to different bytecode. That is a formatter bug: report it, and do not format these by hand.", file=sys.stderr)
        return 1
    return 1 if args.check and change else 0


if __name__ == "__main__":
    sys.exit(main())
