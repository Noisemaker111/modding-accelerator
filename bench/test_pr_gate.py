"""The PR hook refuses attempt-numbered titles and bodies with no "Carries over", and lets the rest through.
Run: python bench/test_pr_gate.py   (runs the hook as Claude Code does: JSON on stdin, exit 2 blocks)
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HOOK = Path(__file__).resolve().parent.parent / "tools" / "hooks" / "pr_gate.py"
GOOD = "**What works now that didn't:** x\n\n**Carries over:** the prescreen is an mmt feature for any mod."
checks = 0


def run(command):
    event = {"tool_name": "Bash", "tool_input": {"command": command}}
    done = subprocess.run([sys.executable, str(HOOK)], input=json.dumps(event), capture_output=True, text=True)
    return done.returncode, done.stderr


def expect(command, code, says=""):
    global checks
    got, err = run(command)
    if got != code or says not in err:
        raise AssertionError(f"{command!r}: exit {got}, wanted {code} saying {says!r}; stderr: {err}")
    checks += 1


body = json.dumps(GOOD)
expect(f"gh pr create --title 'Diorama round 5: 2x faster' --body {body}", 2, "round 5")
expect(f"gh pr create --title 'Coverage batch 3' --body {body}", 2, "batch 3")
expect(f'"C:/Program Files/GitHub CLI/gh.exe" pr create --title "Port part 2" --body {body}', 2, "part 2")
expect("gh pr create --title 'Map tiles reuse buffers' --body 'no line here'", 2, "Carries over")
expect("gh pr create --title 'Map tiles reuse buffers' --fill", 2, "--body")
expect(f"gh pr create --title 'Map tiles reuse buffers, 2.0x' --body {body}", 0)
expect("gh pr create --title 'Turn round the map' --body " + body, 0)  # "round" alone is a word, not an attempt
expect("gh pr edit 98 --add-label perf", 0)
expect("gh pr edit 98 --title 'Diorama round 6'", 2, "round 6")
expect("gh pr list --search 'round 5'", 0)
expect("git commit -m 'round 5'", 0)
expect("echo 'gh pr create --title \"Convert round 6\" --body x' | grep round", 0)  # text, not a gh call
expect("cd repo && PATH=/x gh pr create --title 'Convert round 6' --body " + body, 2, "round 6")
expect("& 'C:/Program Files/GitHub CLI/gh.exe' pr edit 5 --title 'Sweep round 3'", 2, "round 3")
with tempfile.TemporaryDirectory() as d:
    good, bad = Path(d, "good.md"), Path(d, "bad.md")
    good.write_text(GOOD, encoding="utf-8")
    bad.write_text("**Proof:** CI", encoding="utf-8")
    expect(f"gh pr create --title 'Map tiles' --body-file {good.as_posix()}", 0)
    expect(f"gh pr create --title 'Map tiles' --body-file {bad.as_posix()}", 2, "Carries over")
print(f"{checks} PR hook checks passed")
