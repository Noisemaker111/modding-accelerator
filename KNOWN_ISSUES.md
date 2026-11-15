# Known issues

Traps that cost agents time. One entry each: symptom, cause, fix. Add one when a trap costs you more than a few
minutes; delete it when the cause is gone.

## A search for a jar or source file runs for hours
- Cause: `find /` or a whole-drive search. One ran for 11 hours.
- Fix: Gradle's dependencies and sources jars are under `~/.gradle/caches/modules-2/files-2.1/GROUP/NAME/`, Loom's
  game jars under `~/.gradle/caches/fabric-loom/`, modaccel's JDKs, servers and packages under `~/.modaccel/`.

## The PC freezes during a check
- Cause: game clients, pack servers, port boards or A/B timing run locally, often several agents at once.
- Fix: run them in the cloud (`tools/cloud-run`, AGENTS.md). Locally: one game at a time, `port_board.py --changed`,
  `pack-check --focus MODID`.

## Self-test passes in the checkout and fails in CI
- Cause: the jar ships only `Installation.RESOURCES` (directories there are packed whole); a file the CLI reads that is
  not listed is missing from `~/.modaccel/packages/<hash>`.
- Fix: list it in `RESOURCES`; run `python bench/run_tests.py --only self-test`, which tests the packaged jar.

## `gh: command not found` on Windows
- Fix: `export PATH="/c/Program Files/GitHub CLI:$PATH"`.

## Shell heredocs and `sed` mangle backslashes and quotes on Windows
- Fix: edit files with the Edit/Write tools. For a regex in Java source, `[0-9]` avoids the backslash entirely.

## Game code that compiles and misbehaves
- `MouseButtonInfo`'s left button is 1. Build an `ItemStack` after datapack load. `BlockBehaviour.Properties` needs
  `.setId(...)`. Multiplayer block entities need both `getUpdatePacket()` and `getUpdateTag()`.
- Adding fields or methods while hot reloading needs `--jbr`.
