# Modding Accelerator

`modaccel` tests Minecraft mods/plugins, ports mod source between versions, and diagnoses modpacks.
The root is also Prism Lab, the demo mod fixture. Scope: `README.md` and `SCOPE.md`.

## Commands

From the repo root, with JDK 17+ and Python 3.12+. No install step: the launcher compiles itself.
POSIX below; Windows uses `modaccel.cmd` and `python` instead of `./modaccel` and `python3`.

```sh
./modaccel help
./modaccel doctor --json
./modaccel fast
./modaccel package
```

Use modaccel for builds and game launches; never raw Gradle or `--no-daemon`.
Once per clone: `git config core.hooksPath .githooks`. Its pre-push hook runs the fast offline checks on what the push changes (`tools/ci/pre_push.py`, seconds).
The PR's CI is the gate; don't wait on local heavy checks. It runs only the plans the diff reaches (`tools/ci/reach.py`) on all three systems.
Reproduce a plan locally only to debug it: `python3 tools/ci/lanes.py core` (or `server`, `stonecutter`, `--seed N`).
Never retry a failing check or widen its timeout: find why it failed.

## Branches and PRs

One idea = one branch (`feat/<idea>`) = one PR. Work, build and run checks in the branch's own worktree, never in the main checkout:
`git worktree add ../modding-accelerator-<idea> -b feat/<idea> origin/main`. Commit to the idea's branch as you go.
Open the PR as a draft (`gh pr create --draft --base main`) to get CI; when the idea is done, wait for its checks (`gh pr checks --watch`), then mark it ready and merge (`gh pr merge --squash`) only if they pass.
Parallel threads on one idea branch off the idea branch and merge back into it, not into `main`.
An unrelated fix gets its own idea branch and PR; never tack it onto another PR. PR text: `.github/pull_request_template.md`.

## Start here

- CLI: `tools/cli/Modaccel.java` (entry/help), one class per command; `tools/tests/PortableTests.java` (self-test).
- Game probes: `tools/{agent,datacheck,mixincheck,packcheck,plugincheck,servercheck}/`.
- Modpacks: `tools/cli/PackCheck.java` runs the registries `PackInputs` (pack shapes), `PackLoaders` with `knowledge/pack-loaders.json` (loader servers), `PackMods` (jar metadata), `PackChecks` and `PackRepairs` with `knowledge/pack-fixes.json`; the loader-neutral probe is `tools/packcheck/`. Extending them: `tools/packcheck/CONTRIBUTING.md`.
- Porter/rules: `tools/cli/Port.java`, `knowledge/fixes.json`, `coverage/changes/`. Run `python3 bench/port_board.py` when changing them; each rule needs a `test {error,before,after}`. Writer: `python3 bench/rule_test.py --help`.
- Optimizing a mod: `perf` then `perf-optimize` (PERFORMANCE.md); each run writes record.json. Keep runs with `python3 bench/record.py perf`, summarise with `python3 bench/perf_sessions.py`; accepted changes become `knowledge/perf-patterns.json` entries, each citing its kept run.
- Port receipts and measures: `coverage/{ledger,measures}.json`; recording commands in `bench/README.md`. Plans, dated status and findings belong in PRs/issues, never docs or memory notes.
- Desktop app: `app/` (Rust, Rune pinned in `app/Cargo.toml`) runs the CLI with `--json` and `MODACCEL_PROGRESS=1`; headless UI scripts are in `app/ui-tests/`.
- Website: `bench/bench_page.py`, `bench/page_style.py`; map uses `bench/porting_map.py`. `docs/` is generated on main; change its generators. Keep docs about current use, not history or plans.

## Gotchas

- Environment traps (Windows PATH, shims, quoting): `KNOWN_ISSUES.md`. Add one when a trap costs you minutes.
- Builds, MockBukkit and loading prove no behaviour: assert mod state in a real client, or a plugin scenario on real Paper. Rendering needs an inspected 1280x720 game framebuffer. Separate build/start/behaviour timings and cold/warm caches.
- Keep JBR's `-XX:TieredStopAtLevel=1`: C2 with hot redefinition crashes. Adding fields/methods needs JBR; registry/mixin edits need restart. Multiplayer block entities need both `getUpdatePacket()` and `getUpdateTag()`.
- Game UI: `MouseButtonInfo`'s left button is 1. Build `ItemStack` after datapack load; `BlockBehaviour.Properties` needs `.setId(...)`.
- The pack probe is written in Mojang names; each ART pass needs `--lib` in its input's names. Keep shipped/repaired pack findings separate; failed servers can leave JVMs alive.
- Java: one statement per line. Older PowerShell/`Lab*.java` lanes are retained; don't extend them.
- Scratch, logs and cloned trials belong in ignored `.runs/`.
