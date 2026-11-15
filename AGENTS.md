# Modding Accelerator

`modaccel` tests Minecraft mods/plugins, ports mod source between versions, and diagnoses modpacks.
The root is also Prism Lab, the demo mod fixture. Scope: `README.md`, `SCOPE.md`. Traps: `KNOWN_ISSUES.md`.

## Commands

JDK 17+ and Python 3.12+; no install step. Windows: `modaccel.cmd` and `python` for `./modaccel` and `python3`.

```sh
./modaccel help                    # every command
python3 bench/run_tests.py         # every offline check, as CI runs them (--list, --only NAME)
tools/cloud-run packs|ports|perf   # heavy jobs on GitHub's runners (below)
```

Use modaccel for builds and game launches, never raw Gradle or `--no-daemon`.

## Rules

- **One feature = one branch = one PR.** Commit after each passing step; push to the same PR. Use
  `.github/pull_request_template.md`. Merge (`gh pr merge --squash`) only when every check is green; CI is the gate,
  and a PR's claims ("tests pass", numbers) must be ones CI or a linked cloud run shows.
- **Never run heavy jobs on this PC.** Pack sweeps, the port board, perf/Diorama A/B timing and benchmarks run in the
  cloud: push, then `tools/cloud-run packs [-f packs=ID] [-f focus=MODID] [-f fix=true]`, `tools/cloud-run ports
  [-f cases=ID]` or `tools/cloud-run perf [-f patch=FILE]`; it waits with a hard timeout and downloads to
  `.runs/cloud/`. Locally: `python3 bench/port_board.py --changed` (only cases your rules cross), `pack-check --focus
  MODID` (one mod and its dependencies), one game at a time.
- **Tests: follow `~/.claude/skills/minimal-tests`.** A test proves something users depend on, fails on a wrong
  answer (vary inputs; change cached inputs mid-run), and runs against the packaged jar. Delete tests that can't
  fail. Budgets (enforced by `bench/test_budgets.py`): PR job under 3 min, no test file over 800 lines, this file
  under 100 lines. To add past a budget, delete first. A new check needs `bench/run_tests.py`, nowhere else.
- **Never search the whole disk.** Gradle's sources jars and dependencies are under `~/.gradle/caches`
  (`modules-2/files-2.1/GROUP/NAME`, Loom's game jars in `fabric-loom/`); ours are in `~/.modaccel`.
- Scratch, logs and clones go in ignored `.runs/`. Plans and dated status go in PRs/issues, not docs.

## Where things are

- CLI: `tools/cli/Modaccel.java` (entry/help), one class per command. Self-test suites: `tools/tests/`, listed in
  `tools/cli/Installation.java` (`SUITES`); everything the jar ships is `Installation.RESOURCES`.
- Game probes: `tools/{agent,datacheck,mixincheck,packcheck,plugincheck,servercheck}/`. Modpacks: `tools/cli/Pack*.java`
  (`tools/packcheck/CONTRIBUTING.md`).
- Porter: `tools/cli/Port.java`, rules `knowledge/fixes.json` (each needs `test {error,before,after}`:
  `python3 bench/rule_test.py --help`), map `coverage/changes/`.
- Perf: `perf`, `perf-optimize` (PERFORMANCE.md); kept runs `coverage/perf-runs.json` (`python3 bench/record.py perf`),
  patterns `knowledge/perf-patterns.json`, each citing a kept run.
- Receipts: `coverage/{ledger,measures}.json` (`bench/README.md`). Desktop app: `app/` (Rust on Rune, `cargo test`).
- Website: `bench/bench_page.py`, `bench/porting_map.py`; `docs/` and `ROADMAP.md` are generated on main.
- CI: `.github/workflows/portable.yml` (required: linux, windows), `integration.yml` (servers, Stonecutter),
  `app.yml`, `cloud-*.yml` (dispatched by `tools/cloud-run`).

## Gotchas

- Builds, MockBukkit and loading prove no behaviour: assert mod state in a real client, or a plugin on real Paper.
- Keep JBR's `-XX:TieredStopAtLevel=1` (C2 with hot redefinition crashes). Registry/mixin edits need a restart.
- The pack probe is written in Mojang names; each ART pass needs `--lib` in its input's names.
- Java: one statement per line. Older PowerShell/`Lab*.java` lanes are retained; don't extend them.
