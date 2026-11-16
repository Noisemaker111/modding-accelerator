"""Claude Code PreToolUse hook: refuses `gh pr create` / `gh pr edit` calls that break how this repo ships.

- A title numbered as an attempt ("round 5", "batch 3", "part 2", "phase 4") is one goal re-shipped in pieces.
  Title the PR by what works now; keep a goal in one draft PR until its bar is met.
- A PR body without "Carries over" leaves out what the next mod gets from this work (the template's line).

Reads the hook's JSON on stdin. Exit 2 blocks the call and the message goes back to the agent; anything it cannot
parse is let through.
"""
import json
import re
import shlex
import sys
from pathlib import Path

ATTEMPT = re.compile(r"\b(round|batch|part|phase|attempt|take)\s*#?\d+", re.IGNORECASE)
CARRIES = re.compile(r"carries\s+over", re.IGNORECASE)
# gh run as a command (start of line or after ; & | ( , maybe PowerShell's & or VAR=x), not text in an echo or grep.
PR_COMMAND = re.compile(r"(?:^|[;&|(\n])\s*(?:&\s*)?(?:\w+=\S*\s+)*"
                        r"(?:\"[^\"\n]*?gh(?:\.exe)?\"|'[^'\n]*?gh(?:\.exe)?'|[^\s;&|()\"']*gh(?:\.exe)?)\s+pr\s+(?:create|edit)\b")


def option(words, *names):
    for i, word in enumerate(words):
        for name in names:
            if word == name and i + 1 < len(words):
                return words[i + 1]
            if word.startswith(name + "="):
                return word.split("=", 1)[1]
    return None


def problems(command):
    """What is wrong with this shell command's PR, or [] when it is fine or not a PR create/edit."""
    if not PR_COMMAND.search(command):
        return []
    try:
        words = shlex.split(command, posix=True)
    except ValueError:
        return []
    found = []
    title = option(words, "--title", "-t")
    if title and ATTEMPT.search(title):
        found.append(f'title "{title}" numbers an attempt ("{ATTEMPT.search(title).group(0)}"). Name the PR by what '
                     "works now, and push the rest of one goal to the same PR.")
    creating = re.search(r"\bpr\s+create\b", command) is not None
    body = option(words, "--body", "-b")
    body_file = option(words, "--body-file", "-F")
    if body_file and body_file != "-":
        try:
            body = Path(body_file).read_text(encoding="utf-8", errors="replace")
        except OSError:
            return found  # a path this parse mangled (Windows backslashes): don't guess
    if body is not None and not CARRIES.search(body):
        found.append('the body has no "Carries over" line: what the next mod, of any kind, gets for free from this '
                     "work (a general pattern, tool, generator or skill). See .github/pull_request_template.md.")
    if creating and body is None and not body_file:
        found.append("give the body with --body or --body-file, filled from .github/pull_request_template.md.")
    return found


def main():
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    command = (event.get("tool_input") or {}).get("command") or ""
    found = problems(command)
    if not found:
        return 0
    print("PR refused by tools/hooks/pr_gate.py:\n- " + "\n- ".join(found), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
