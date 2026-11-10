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
Offline gate before pushing (game/network checks: `.github/workflows/portable.yml`):

```sh
./modaccel self-test &&
python3 bench/test_measures.py &&
python3 bench/test_record.py &&
python3 bench/test_ledger_rule.py &&
python3 bench/test_java_lines.py &&
python3 bench/test_loader_api.py &&
python3 bench/test_session_cost.py
```

Commit and push your branch. Use `.github/pull_request_template.md`; land on `main`:

```sh
gh pr create --base main --fill
gh pr merge --squash
```

## Start here

- CLI: `tools/cli/Modaccel.java` (entry/help), one class per command; `tools/tests/PortableTests.java` (self-test).
- Game probes: `tools/{agent,datacheck,mixincheck,packcheck,plugincheck,servercheck}/`.
- Porter/rules: `tools/cli/Port.java`, `knowledge/fixes.json`, `coverage/changes/`. Run `python3 bench/port_board.py` when changing them; each rule needs a `test {error,before,after}`. Writer: `python3 bench/rule_test.py --help`.
- Port receipts and measures: `coverage/{ledger,measures}.json`; recording commands in `bench/README.md`. Plans, dated status and findings belong in PRs/issues, never docs or memory notes.
- Website: `bench/bench_page.py`, `bench/page_style.py`; map uses `bench/porting_map.py`. `docs/` is generated on main; change its generators. Keep docs about current use, not history or plans.

## Gotchas

- Builds, MockBukkit and loading prove no behaviour: assert mod state in a real client, or a plugin scenario on real Paper. Rendering needs an inspected 1280x720 game framebuffer. Separate build/start/behaviour timings and cold/warm caches.
- Keep JBR's `-XX:TieredStopAtLevel=1`: C2 with hot redefinition crashes. Adding fields/methods needs JBR; registry/mixin edits need restart. Multiplayer block entities need both `getUpdatePacket()` and `getUpdateTag()`.
- Game UI: `MouseButtonInfo`'s left button is 1. Build `ItemStack` after datapack load; `BlockBehaviour.Properties` needs `.setId(...)`.
- Forge probes use Mojang names; ART's `--lib` must too. Keep shipped/repaired pack findings separate; failed servers can leave JVMs alive.
- Java: one statement per line. Older PowerShell/`Lab*.java` lanes are retained; don't extend them.
- Scratch, logs and cloned trials belong in ignored `.runs/`.
