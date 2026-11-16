# Known issues

Traps that cost agents time. One entry each: symptom, cause, fix. Add one when a trap costs you more than a few
minutes; delete it when the cause is gone.

## A search for a jar or source file runs for hours
- Cause: `find /` or a whole-drive search. One ran for 11 hours.
- Fix: Gradle's dependencies and sources jars are under `~/.gradle/caches/modules-2/files-2.1/GROUP/NAME/`, Loom's
  game jars under `~/.gradle/caches/fabric-loom/`, mmt's JDKs, servers and packages under `~/.mmt/`.

## The PC freezes during a check
- Cause: orphaned and duplicate game launches. Games started through Gradle `:runClient` were parented by the Gradle
  daemon and outlived the command that started them (two ran for 13 hours), and new checks launched more beside them.
- Fix: mmt now starts games and servers as its own children and ends them when the start fails, is interrupted
  or is killed (`Run.Owned`); a hidden game left with no requests quits after 15 minutes. `game-stop` when done.
  There is no cap on local games; full sweeps (all packs, the whole port corpus) go to `tools/cloud-run`.

## A hidden game keeps running after the command that started it
- Cause: by design `ui-check`/`world-check` leave the game up for `reload` and `eval`. A failed, interrupted or
  SIGTERM'd start now takes its game down (`Run.Owned`), but a hard kill of mmt alone runs no cleanup.
- Fix: `mmt game-stop --root R` when done. A hidden game with no mmt request for 15 minutes quits itself.

## Self-test passes in the checkout and fails in CI
- Cause: the jar ships only `Installation.RESOURCES` (directories there are packed whole); a file the CLI reads that is
  not listed is missing from `~/.mmt/packages/<hash>`.
- Fix: list it in `RESOURCES`; run `python bench/run_tests.py --only self-test`, which tests the packaged jar.

## perf-optimize stops at once: "The fingerprint gate could not read the mod's Minecraft version"
- Cause: the built-in fingerprint gate reads the version from `ProjectModel.discover()`, which only looks at
  `build.gradle(.kts)` files. A Stonecutter build (Diorama: `build.forge.gradle.kts`, the version in
  `versions/1.21.1/gradle.properties`) gives it none, whatever the experiment's `project`.
- Fix: until it reads the project's own `gradle.properties`, set `"fingerprint": false` in the experiment. A client-only
  mod that changes no world state (a map) gains nothing from the gate anyway.

## perf-optimize: an arm's build fails with "Failed in invoke method" while Gradle loads it
- Cause: each arm is a `git worktree`, whose `.git` is a file. A build that reads its version from git with JGit
  (palantir `versionDetails()`, `grgit.status()`; ModernFix) cannot open it. Builds that run the `git` command work.
- Fix: in the one local commit both arms share, set the version the pack ships instead of reading git.

## `gh: command not found` on Windows
- Fix: `export PATH="/c/Program Files/GitHub CLI:$PATH"`.

## Shell heredocs and `sed` mangle backslashes and quotes on Windows
- Fix: edit files with the Edit/Write tools. For a regex in Java source, `[0-9]` avoids the backslash entirely.

## Game code that compiles and misbehaves
- `MouseButtonInfo`'s left button is 1. Build an `ItemStack` after datapack load. `BlockBehaviour.Properties` needs
  `.setId(...)`. Multiplayer block entities need both `getUpdatePacket()` and `getUpdateTag()`.
- Adding fields or methods while hot reloading needs `--jbr`.
