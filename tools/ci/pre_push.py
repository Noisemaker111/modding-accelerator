"""The offline checks a push can reach that finish in seconds, on the files the push changes. Run by .githooks/pre-push
(git config core.hooksPath .githooks); by hand: python tools/ci/pre_push.py [BASE]. CI runs everything, including
the slower bench tests (test_record, test_session_cost, diorama/test_prepare_comparison) and every Gradle and server
gate; this only catches what is cheap to catch before the push leaves the machine.
"""
import glob, os, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from reach import reach  # noqa: E402

# Bench tests that take over ~5 s on a laptop; CI's core plan runs them.
SLOW = {"bench/test_record.py", "bench/test_session_cost.py", "bench/diorama/test_prepare_comparison.py"}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout


def changed(base):
    return [p for p in git("diff", "--name-only", "--no-renames", f"{base}...HEAD").splitlines() if p]


def base_from_stdin():
    """git gives a pre-push hook '<local ref> <local sha> <remote ref> <remote sha>' per ref."""
    bases = []
    for line in sys.stdin.read().splitlines():
        parts = line.split()
        if len(parts) == 4 and set(parts[1]) != {"0"}:
            remote = parts[3]
            bases.append(remote if set(remote) != {"0"} and git("cat-file", "-t", remote).strip() else None)
    known = [b for b in bases if b]
    if known and len(known) == len(bases):
        return known[0]
    # A new branch: compare with where it left main.
    return git("merge-base", "HEAD", "origin/main").strip() or "HEAD~1"


def checks(paths):
    out = []
    if not any(reach(p) for p in paths):
        return out
    python = sys.executable
    for test in sorted(glob.glob("bench/**/test_*.py", recursive=True, root_dir=ROOT)):
        if Path(test).as_posix() not in SLOW:
            out.append((Path(test).as_posix(), [python, test]))
    out.append(("tools/ci/test_reach.py", [python, "tools/ci/test_reach.py"]))
    java = [p for p in paths if p.startswith("tools/cli/") and p.endswith(".java") and (ROOT / p).exists()]
    if java:
        # The changed files against the last compiled launcher: catches what they break in themselves in seconds;
        # a caller broken by a changed signature shows up in CI (or ./modaccel help, which recompiles everything).
        built = sorted((ROOT / ".modaccel/cli").glob("*/build.stamp"), key=lambda p: p.stat().st_mtime)
        classpath = [str(built[-1].parent)] if built else []
        target = tempfile.mkdtemp(prefix="modaccel-prepush-")
        out.append(("javac changed tools/cli", ["javac", "--release", "17", "-encoding", "UTF-8", "-nowarn", "-d", target]
                    + (["-cp", classpath[0]] if classpath else []) + (java if classpath else glob.glob("tools/cli/*.java", root_dir=ROOT))))
    return out


def run(item):
    name, command = item
    began = time.monotonic()
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return name, result.returncode, time.monotonic() - began, result.stdout + result.stderr


def main(argv):
    began = time.monotonic()
    base = argv[0] if argv else base_from_stdin()
    paths = changed(base)
    todo = checks(paths)
    if not todo:
        print(f"pre-push: {len(paths)} changed files reach no check")
        return 0
    with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        results = list(pool.map(run, todo))
    failed = [r for r in results if r[1] != 0]
    for name, code, seconds, text in failed:
        print(f"FAIL {name} ({seconds:.1f}s)\n{text}")
    print(f"pre-push: {len(results) - len(failed)}/{len(results)} checks passed in {time.monotonic() - began:.1f}s "
          f"on {len(paths)} changed files" + ("; push stopped (git push --no-verify skips this)" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
