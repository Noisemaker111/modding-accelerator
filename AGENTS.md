# Minecraft Modding Tools

`mmt` tests Minecraft mods/plugins, ports mod source between versions, and diagnoses modpacks.
Prism Lab, the demo mod fixture, is `fixtures/prism-lab/`. Scope: `README.md`, `SCOPE.md`. Traps: `KNOWN_ISSUES.md`.

## Commands

JDK 17+ and Python 3.12+; no install step. Windows: `mmt.cmd` and `python` for `./mmt` and `python3`.

```sh
./mmt help                    # every command
python3 bench/run_tests.py         # every offline check, as CI runs them (--list, --only NAME)
tools/cloud-run packs|ports|perf|convert   # full sweeps on GitHub's runners (below)
```

Use mmt for builds and game launches, never raw Gradle or `--no-daemon`.

## Rules

- **Compounding tooling: every change makes the next mod faster to improve, whatever kind of mod it is.** Leave
  behind a general pattern, tool, generator or skill, not a one-mod fix; the PR's "Carries over" line says what.
- **Before contributing or asking, read `CONTRIBUTING.md`,** then check the code, issues and PRs.
  Unwritten choices are yours; third-party files (game jars, mappings, mods) are downloaded at run time, not added to the repo.
- **One feature = one branch = one PR**, titled by what works now, never "round/batch/part N" (a hook in
  `.claude/settings.json` refuses those). Commit after each passing step; push to the same PR. Use
  `.github/pull_request_template.md`. Merge (`gh pr merge --squash`) only when every check is green; CI is the gate,
  and a PR's claims ("tests pass", numbers) must be ones CI, a linked cloud run or the local in-game run's receipt shows.
- **Prove every change in-game locally before merge, at the smallest real scope:** `pack-check --focus MODID`, one
  mod plus its dependencies, a headless server when the metric is server-side, `python3 bench/port_board.py
  --changed`. Shut it down afterwards (`game-stop`). Local timing comparisons interleave A/B runs. "Not tested
  in-game" or "saved for the cloud" is never an acceptable PR result.
- **Only full sweeps go to the cloud** (all packs, the whole port corpus, wide A/B timing): push, then
  `tools/cloud-run packs|ports|perf|convert [-f KEY=VALUE]`; it waits with a hard timeout and downloads to `.runs/cloud/`.
- **Tests: follow `~/.claude/skills/minimal-tests`.** A test proves something users depend on, fails on a wrong
  answer (vary inputs; change cached inputs mid-run), and runs against the packaged jar. Delete tests that can't
  fail. Budgets (enforced by `bench/test_budgets.py`): PR job under 3 min, no test file over 800 lines, this file
  under 100 lines. To add past a budget, delete first. A new check needs `bench/run_tests.py`, nowhere else.
- **Never search the whole disk.** Gradle's sources jars and dependencies are under `~/.gradle/caches`
  (`modules-2/files-2.1/GROUP/NAME`, Loom's game jars in `fabric-loom/`); ours are in `~/.mmt`.
- Scratch, logs and clones go in ignored `.runs/`. Plans and dated status go in PRs/issues, not docs.

## Where things are

- CLI: `tools/cli/Mmt.java` (entry/help), one class per command. Self-test suites: `tools/tests/`, listed in
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
