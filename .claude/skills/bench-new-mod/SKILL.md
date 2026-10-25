---
name: bench-new-mod
description: Take an unfamiliar Minecraft mod or plugin repo and find out everything about it with modaccel - its own workflow, a before/after bench, whether we support it, which versions it can run on, how we could help, and whether it joins the test suite. Use when Jon pastes a mod/plugin repo link or says "bench this mod", "try our thing on this", "new mod".
---

# Bench a new mod

Goal: one plain report Jon can judge, answering:
- Does it work with modaccel?
- How much faster is it?
- Which versions can it run on?
- How can we help?
- Should it join our test suite?

Every number comes from a real run, and anything not seen is marked "unverified".

## Rules
- Read AGENTS.md, README.md, `modaccel.cmd help` and `support` first.
- Don't install system software (Docker, WSL, Node). If something is missing, reproduce the steps natively and mark those numbers unmeasured.
- Use no real API keys and create no accounts. Use the repo's mocks.
- Don't message the author, open issues or publish anything without asking Jon.
- Run their own commands only for the "before" baseline. Everything else goes through modaccel.
- Don't edit modaccel in the main checkout. Fixes go in a separate worktree and branch, measured there.
- Leave coverage/ledger.json alone unless a port actually ran.

## Steps
1. **Pin it.** Clone into `.runs/beta-trials/<name>` and record the commit hash.
2. **Learn their workflow.** Read the build files, dev and run scripts, compose files, tests and mod metadata (fabric.mod.json, neoforge.mods.toml, plugin.yml). Write down:
   - loader or platform, game version and Java;
   - how they build and run;
   - what their tests check.
3. **Before bench, their way.**
   - Cold build with an empty Gradle home.
   - Warm builds the way their script does them: no change, a one-line edit, the jar only, the tests only.
   - Game or server start until the mod is loaded.
   - The full edit → running loop, three times.
   - Their e2e tests.
   - Note the cache state for every number.
4. **After bench, modaccel.** Run `doctor`, `setup`, `compile`, `fast`, `test`, `ui-check`, `world-check`, `mp-check`, `eval` and `reload` in that order, with the same edits.
   - Record the exact error for each one that fails.
   - Try `--task` and `--project` variants to find what already works.
5. **Smallest fix.** If one small change unblocks a step, make it in a worktree, measure it and keep it uncommitted. Report it; don't land it.
6. **Versions.**
   - Sweep older and newer versions without editing their files: a swap-in script, or `port` where it's supported.
   - List the missing classes or methods per version to find the lowest version it can reach, and explain why.
   - Prove that lowest version and its neighbour at runtime with one jar.
   - Rerun anything that fails once before calling it a version bug.
7. **Clean up.** Revert edits, stop servers, daemons and games, and check that no ports are left open.

## Report (to Jon, plain words, outcome first)
Classify it with SCOPE.md: its platform, its side, the highest support rung it passes, and the intake decision from SCOPE.md section 4.
1. **Verdict:** supported yes/no, plus one line on why. Give the platform, side and rung.
2. **Their workflow:** three to five bullets.
3. **Before/after table:** step, cache state, their time, modaccel time.
4. **Blockers:** the exact errors, each with what would fix it.
5. **Versions table:** version, compiles, runs, errors or reason.
6. **How we can help, ranked:** each item with measured or estimated gain and rough effort, estimates labelled.
7. **Test suite:** add it or not. If yes, which tier: cheap checks in CI, game checks on Jon's PC, or both. Say what new kind of build it covers that our current mods don't.
8. **Unverified:** everything not seen.

Also finish with the step checklist you actually followed, so this skill can be improved.

First run: AgentCraft (mezotv/agentcraft, a Paper plugin) on 2026-10-25. Docker was missing, so it was reproduced natively. Not supported yet: `fast` needed "pure" tags, and there's no Paper server check. One jar covers Paper 1.21.10–26.3. Recommended as the first server-plugin fixture.
