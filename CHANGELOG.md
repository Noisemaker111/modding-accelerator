# Changelog

## Unreleased: resource checks follow the real loaders

PR #28's resource checks failed real mods the game loads. Run on 25 real mod builds, they wrongly
failed Architectury (fabric, neoforge), Sodium (fabric, neoforge) and MineColonies; now none do.

- **JSON is read the way Gson reads it** (Fabric Loader's strict copy, Mixin's and Minecraft's lenient
  reader): a byte order mark, raw tabs in strings and duplicate keys (the last one wins) are fine;
  comments and single quotes fail only fabric.mod.json, whose reader is strict. JSON Minecraft cannot
  read even leniently fails only under assets/, data/ or pack.mcmeta; anywhere else it is a warning.
- **Mixin configs, mixins and entrypoints are looked up the way the game finds them:** the whole
  runtime classpath (Architectury's common jar), other projects' sources and resources beside their
  classes folders (Sodium's common and frapi, not compiled at compile time); a copy anywhere else in
  the checkout makes a missing one a warning. A missing mixin is a problem only in a `"required": true`
  config (Mixin only warns otherwise); a Kotlin-adapter entrypoint is never certain.
- **Ids follow each loader:** a NeoForge 1.21+ dotted id is a warning, not a problem; metadata for a
  loader the build does not run on is not held to that loader's rules; a `MOD_ID` that differs from
  the metadata is a warning (compat code keeps other mods' ids). ForgeGradle 7's shared
  `build/sourceSets/main` output is now checked at all.

## Earlier: stranger pass

What an outside modder hit using modaccel on Diorama and F3Nope from the README alone, fixed.

- **Words, not JSON.** Every failure prints `<command>: failed`, a plain message and the receipt
  path; setup, discover, doctor, fast, test, game-status and game-stop print short text (`--json` keeps
  every field). `help` lists every command, with per-command help for fast, test and compile.
- **Resources and metadata are checked after compile, fast and test**: every JSON file must parse
  (file, line and column), fabric.mod.json / neoforge.mods.toml need a valid mod id, listed mixin
  configs, mixin classes and entrypoints must exist, and a `MOD_ID` in code must match the metadata.
  A crafted item with no model or no en_us name is a warning. A broken recipe or fabric.mod.json
  used to pass.
- **Compiler errors** are `file:line message` with the code line; a test without JUnit gets the
  two dependency lines. An unreachable repository is called the network, not a missing dependency.
  A member NeoForge's own patches add but the compile could not find is called a stale NeoForge
  cache, decided by NeoForge's userdev patch text, naming the jar to delete.
- **One command at a time per project.** A second command is refused and names the first,
  instead of breaking its build with Gradle's missing-temporary-file errors.
- **An interrupted port** is named by the next command, blocks another port, and `port --undo`
  (no id needed: the newest port) restores it, keeping the half-ported copies beside the backups.
- **Wrong input fails fast:** a Minecraft version that is not a release (1.21.66), a `--from` that
  is not the project's, a `--project` without its colon, an unknown project or command (with a
  "did you mean"), `--root src`, a read-only file during a port (nothing changed), Java 8 on PATH or
  JAVA_HOME (the launchers and the jar say which Java they need; a source checkout says it needs a JDK).
- **fast and test** name failing tests and their messages, and say why nothing ran (no tests, or
  none tagged pure). explain calls `Math.rond` a misspelling, read from the build's own JDK.
- **Game checks** show the crash's deepest cause and the first frame in the mod's code, refuse
  before launching when setup recorded runtime files it could not download, and mixin-check names
  `require = 0` injections that matched nothing and so never run.
- The Maven range test always compares against maven-artifact 3.9.9 (cached, or fetched and
  pinned by SHA-1) and fails loudly when it cannot be had.

## Unreleased: hardening pass

modaccel no longer fails, stops or passes without saying why.

- **Port tool fixes from the five-mod trial** (PR #1, adapted): static `@Inject` handlers on
  static targets pass the mixin audit; a compile-only integration with no target release is
  kept instead of blocking; a dependency release built by a newer Loom than the project's is
  skipped for one it can read; Modrinth's shared multi-loader version numbers use the exact
  version id (Serene Seasons resolved to the Forge jar); `fabric.mod.json` keeps its layout and
  only `depends.minecraft` changes, and the packaged manifest is read back; the ui-check frame
  hook no longer crashes clients without Fabric API; the client agent can report which mods
  loaded and waits past the title fade.
- **A missing dependency names itself.** Gradle's "What went wrong" block is shown with every
  coordinate it could not resolve and the hosts it searched.
- **The mixin audit never passes on nothing.** A port with mixins but no game jar fails with
  the reason; a mod with no mixins says "no mixins to audit"; targets in no jar are counted.
- **Undo of an interrupted port** refuses, restores nothing and names the changed files and
  the backup folder. The restore plan is written before the first edit.
- **A port that fails for a non-compiler reason** says so with Gradle's failure block instead
  of "0 compiler errors remain ... semantic migration".
- **NeoForge mod dependencies** are listed as unchecked in the port and dependencies results.
- **mp-check's dedicated server** records the real server pid (the process on its port) and,
  when it dies, its exit code, crash report summary and log.
- **Dependency lookups** keep the real network error and the repositories tried instead of
  "not published"; Modrinth full releases are preferred over newer betas, and a beta that is
  the only choice is named in the result.
- **A bad `--root`** or a folder without a Gradle build gets a plain message.
- **Gradle timeouts** name the limit and whether it was a cold first setup (15 min each, now
  that a cold IAS setup finishes in about 76 s); `MODACCEL_GRADLE_TIMEOUT` raises it.

## 0.1.0-beta.1 - 2026-10-24

First published beta. It is a Gradle source-checkout tool: point it at a mod project, name the
target Minecraft version, and get a reversible port with a packaged build, a mixin audit and
separate real-game checks. Every run ends in either a checked result or a named stop.

### What works

- **Fabric ports within 1.21.x.** F3Nope and AppleSkin 1.21.5 -> 1.21.6 and the reverse
  backport; packaged build and mixin audit, then a real client and save checks.
- **NeoForge ports.** ZombieApocalypseAddon 1.21.1 -> 1.21.4 with no hand edits: loader
  version and declared game range rewritten, 19 API errors repaired by rules, built in 75s
  from a fresh copy and 14s when repeated. The ported jar loads in a real
  1.21.4 client and its `/za` command runs.
- **Larger jumps with named stops.** The same mod to 1.21.6 drops from 38 reported errors to
  9 automatically; the rest is the 1.21.6 SavedData codec redesign and owner/saddling API.
  Jade 1.21.1 -> 1.21.6 stops at the 1.21.6 GUI rewrite.
- **Safe undo.** Original bytes, removed sources and a patch are recorded; undo refuses to
  overwrite later edits.
- **Dependency resolution.** Target coordinates are verified against published repositories,
  optional dependencies can be disabled with proof, and anything unresolved stops with the
  selection entry to add.
- **Game checks.** `ui-check` and `world-check` drive the real client, save inspected frame
  captures, and can require a visible change (`--expect-change`) or a save roundtrip.
- **Receipts.** Every command writes `result.json` with stage timings, so a slow run shows
  where the time went.

### What it does not do

- It does not change a mod's loader. Fabric stays Fabric, NeoForge stays NeoForge; Forge
  target setup is manual.
- It does not prove gameplay from a build. Build, mixin audit, client load and behavior are
  separate results, and behavior needs a meaningful assertion.
- It does not port compiled JARs; a Gradle source checkout with a wrapper is required.
- Multi-project and Stonecutter repos are only partly automatic: nodes are listed and build,
  and a node port stops where repairs would need to land in shared annotated sources.
- Kotlin source ports are untested.
- First use in a NeoForge repo pays a few minutes of NeoGradle setup for that project.

### Verified platforms

- Windows: command, packaging, game checks and a no-Java install that downloads a runtime.
- Linux: command, packaging and every portable check pass in hosted CI; game rendering unverified.
- macOS: the same portable checks pass in hosted CI; not exercised by hand.

### Checks that must pass before a port is called good

A port is only "done" when the build passes, mixin targets verify, the packaged metadata
declares the target game version, and a real client loads the result. Anything less is
reported as partial.
