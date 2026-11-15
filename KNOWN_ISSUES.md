# Known issues

Environment traps that cost agents time. One entry each: symptom, cause, fix or workaround. Add one when a trap costs you more than a few minutes; delete it when the cause is gone.

## `bun` fails to start from Node on Windows

- Symptom: a Node script spawning `bun` fails (`spawn bun ENOENT`, or `EINVAL` on `bun.cmd`).
- Cause: the npm-installed shim is `bun.cmd`; Node will not spawn a `.cmd` without `shell: true`.
- Fix: native `bun.exe` is first on PATH on Jon's PC. Elsewhere install native bun, or spawn with `shell: true`.

## `gh: command not found`

- Symptom: `gh` is missing in Git Bash or a fresh shell on Windows.
- Cause: the GitHub CLI installs to `C:\Program Files\GitHub CLI`, which some shells do not have on PATH.
- Fix: it is now first on PATH on Jon's PC; otherwise `export PATH="/c/Program Files/GitHub CLI:$PATH"`.

## Smoke process-list parser crashes on a control byte

- Symptom: a smoke run's process cleanup throws while parsing the Windows process list.
- Cause: a process command line containing a control character breaks the parser.
- Where: appforgutters (`scripts/lib/smokeProcesses.mjs`), not this repository.

## Shell heredocs and `sed` mangle backslashes and quotes on Windows

- Symptom: an edit made through a heredoc, `python -c` or `sed` in Git Bash lands with `\d` turned into `d`, `\\` halved, or quotes lost; the old text then fails to match.
- Cause: Git Bash, MSYS path conversion and the interpreter each unescape the text again.
- Fix: edit files with the Edit/Write tools, not shell heredocs or `sed`. For a regex in Java source, `[0-9]` avoids the backslash entirely.
