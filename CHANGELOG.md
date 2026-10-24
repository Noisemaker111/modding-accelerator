# Changelog

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
- Linux: command, packaging and the portable checks in hosted CI; game rendering unverified.
- macOS: portable checks in hosted CI; not exercised by hand.

### Checks that must pass before a port is called good

A port is only "done" when the build passes, mixin targets verify, the packaged metadata
declares the target game version, and a real client loads the result. Anything less is
reported as partial.
