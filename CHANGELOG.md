# Changelog

## Unreleased

Focus on one promise: a Fabric mod from 1.21.5 to 1.21.6 in one command, loaded in a real client.
Five popular mods the tool was never tuned on were ported and loaded; the first attempt ported 2 of
5, and every failure was a general tool bug, fixed here.

### Fixed

- **Mixin audit on static targets.** An `@Inject` handler on a static method must be static and
  does not take the target instance. The audit expected the opposite, so it failed correct mixins
  (Animatica's `Identifier.of`) and would have passed broken ones.
- **Compile-only integrations no longer block a port.** A `modCompileOnly` mod with no release for
  the target (Falling Leaves → Fabric Seasons) stays at its version; the build decides whether the
  integration still compiles.
- **Dependencies built by a newer Loom.** The newest release can carry files the project's Loom
  cannot read (YACL 3.8.2 on Loom 1.10: "Invalid access widener file header"). The port now picks
  the newest target release built with the project's Loom minor or older, and records what it skipped.
- **Multiloader Modrinth versions.** Modrinth's Maven served the Forge jar for Serene Seasons
  `21.6.0.0`, a number shared by its Fabric, NeoForge and Forge files. A shared number, or a project
  that already pinned an id, now gets the exact Modrinth version id.
- **`fabric.mod.json` keeps its formatting.** Retargeting changes only `depends.minecraft`; it used
  to rewrite the whole file on one line.
- **Packaged Fabric metadata is read back.** A built jar whose `depends.minecraft` excludes the
  target fails the port ("Fabric refuses to load it"). Ranges such as `1.21.x` are understood.
- **Clear stop reasons.** A port that compiles but fails the mixin audit or the packaged build says
  so, instead of "0 compiler errors… needs a semantic migration".
- **`ui-check` no longer crashes mods without Fabric API.** Its render hook loaded `this` at
  every return of `render`, but the shipped game's stack frames mark it dead there; with no mixin
  recomputing them, the verifier refused the class (`VerifyError: Bad local variable type`,
  Krypton). The hook now calls the client's own `getInstance()`.
- **`ui-check` proves the mod loaded.** It asks the running loader for the project's mod ids and
  fails if any is missing; the receipt lists them under `modsLoaded`. The first-run accessibility
  screen and the title screen's 4 s fade-in are now waited out, so the capture shows the finished
  title screen instead of the onboarding page or half-drawn buttons.

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
