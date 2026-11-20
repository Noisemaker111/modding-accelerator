"""Repository hygiene check: no file under knowledge/ that git ignores (an ignore rule for game folders such as world/
once hid a bridge package, so every other checkout lacked it while this one compiled).

    python bench/test_budgets.py    (run by bench/run_tests.py)

Test files have no line-count cap: judge tests by whether each one fails on a real bug rather than restating the code,
never by file length. The PR job's time is not a budget here either: bench/run_tests.py prints the time; make a slow
check faster instead.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def problems():
    found = []
    ignored = subprocess.run(["git", "ls-files", "--others", "--ignored", "--exclude-standard", "knowledge"], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout.split()
    for name in ignored:
        found.append(f"{name} is ignored by git, so no other checkout has it: un-ignore it in .gitignore")
    return found


def main():
    found = problems()
    print("\n".join(found) or "ok: nothing ignored under knowledge/")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
