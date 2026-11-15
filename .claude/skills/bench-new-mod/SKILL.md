---
name: bench-new-mod
description: Take an unfamiliar Minecraft mod or plugin repo and find out everything about it with modaccel - its own workflow, a before/after bench, whether we support it, which versions it can run on, how we could help, and whether it joins the test suite. Use when Jon pastes a mod/plugin repo link or says "bench this mod", "try our thing on this", "new mod".
---

1. Clone into `.runs/beta-trials/<name>` at a pinned commit. Read its build, run scripts, tests and mod metadata.
2. Before: time their own cold build, warm build, one-line edit loop and game/server start (note cache state).
3. After: run `doctor`, `compile`, `fast`, `test`, `ui-check`, `world-check`, `mp-check`, `eval`, `reload`; record
   each exact error. Timing comparisons go to the cloud or are labelled "taken under shared load".
4. Versions: `port` older/newer where supported; find the lowest version and why.
5. Clean up: revert edits, `game-stop`, no servers or ports left. No installs, accounts, keys or upstream contact.
6. Report to Jon, outcome first: supported (SCOPE.md rung), before/after table, blockers with fixes, versions table,
   how we can help (ranked), whether it joins the suite, and everything unverified.
