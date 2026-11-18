"""The repository's file budgets: no test file over 800 lines, and no file under knowledge/ that git ignores (an ignore
rule for game folders such as world/ once hid a bridge package, so every other checkout lacked it while this one
compiled).

    python bench/test_budgets.py    (run by bench/run_tests.py)

The PR job's time is not a budget here: a 180 s limit failed PRs that changed no check, because the same checks take
166 to 188 s from one runner to the next. bench/run_tests.py prints the time; make a slow check faster instead.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_LINES = 800


def test_files():
    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    for name in tracked:
        path = Path(name)
        if path.name.startswith("test_") and path.suffix == ".py" or path.parts[:2] == ("tools", "tests") and path.suffix == ".java" \
                or path.parts[:2] == ("src", "test"):
            yield name


def problems():
    found = []
    for name in test_files():
        lines = len((ROOT / name).read_text(encoding="utf-8").splitlines())
        if lines > TEST_LINES:
            found.append(f"{name} has {lines} lines; the budget is {TEST_LINES}: split it by behaviour or delete tests")
    ignored = subprocess.run(["git", "ls-files", "--others", "--ignored", "--exclude-standard", "knowledge"], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout.split()
    for name in ignored:
        found.append(f"{name} is ignored by git, so no other checkout has it: un-ignore it in .gitignore")
    return found


def main():
    found = problems()
    print("\n".join(found) or "within budget: test files, nothing ignored under knowledge/")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
