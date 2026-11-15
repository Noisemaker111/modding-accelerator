"""The repository's budgets: no test file over 800 lines, AGENTS.md under 100 lines, the PR job under 3 minutes.

    python bench/test_budgets.py                        the file budgets (run by bench/run_tests.py)
    python bench/test_budgets.py --job-started EPOCH    also the job's time so far, against 180 s (CI's last step)

To add past a budget, delete something first.
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_LINES, AGENTS_LINES, JOB_SECONDS = 800, 100, 180


def test_files():
    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    for name in tracked:
        path = Path(name)
        if path.name.startswith("test_") and path.suffix == ".py" or path.parts[:2] == ("tools", "tests") and path.suffix == ".java" \
                or path.parts[:2] == ("src", "test"):
            yield name


def problems(job_started=None):
    found = []
    for name in test_files():
        lines = len((ROOT / name).read_text(encoding="utf-8").splitlines())
        if lines > TEST_LINES:
            found.append(f"{name} has {lines} lines; the budget is {TEST_LINES}: split it by behaviour or delete tests")
    agents = len((ROOT / "AGENTS.md").read_text(encoding="utf-8").splitlines())
    if agents >= AGENTS_LINES:
        found.append(f"AGENTS.md has {agents} lines; the budget is under {AGENTS_LINES}")
    if job_started is not None and time.time() - job_started > JOB_SECONDS:
        found.append(f"the PR job took {time.time() - job_started:.0f} s; the budget is {JOB_SECONDS} s: make a check faster or delete one")
    return found


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--job-started", type=float, help="the job's start, in seconds since the epoch")
    args = ap.parse_args(argv)
    found = problems(args.job_started)
    print("\n".join(found) or "within budget: test files, AGENTS.md" + (", PR job time" if args.job_started else ""))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
