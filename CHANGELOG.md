# Changelog

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
