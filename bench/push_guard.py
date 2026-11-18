"""Before a git push, run the offline checks that take seconds and that CI fails on (Java line length today), so a
push never goes out with a failure found minutes later on CI. A Claude Code PreToolUse hook on Bash runs it
(.claude/settings.json); it reads the hook's JSON on stdin and does nothing unless the command pushes.

Exit 2 blocks the push and shows why; anything else lets it through.
"""

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECKS = [["bench/test_java_lines.py"]]


def main():
    try:
        command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
    except ValueError:
        return 0
    if not re.search(r"\bgit\s+push\b", command):
        return 0
    for check in CHECKS:
        run = subprocess.run([sys.executable, *check], cwd=ROOT, capture_output=True, text=True)
        if run.returncode != 0:
            sys.stderr.write(f"Push stopped: python {' '.join(check)} fails, and CI would fail on it.\n"
                             + (run.stdout + run.stderr)[-2000:])
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
