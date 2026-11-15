<p align="center"><img src="docs/logo.svg" width="64" height="64" alt=""></p>

<h1 align="center">Modding Accelerator</h1>

<p align="center">
  <b>Test a Minecraft mod in seconds, port it to any version, and make modpacks work together.</b><br>
  One command, <code>modaccel</code>. You never open the game to check a change, and never diagnose a conflict by hand.
</p>

modaccel has a Java 17+ CLI and Java game probes, Python bench tools, a JavaScript porting map, Groovy Gradle files, and shell/Windows launchers; older PowerShell tooling remains in the repo.<br>
It drives Gradle builds for Fabric (Loom), NeoForge (ModDevGradle/NeoGradle), Forge (ForgeGradle), and Paper/Bukkit plugins; plugin checks use MockBukkit or real Paper with MCProtocolLib and ViaProxy.<br>
The CLI runs on Windows, Linux and macOS; game checks have platform limits below. The root Gradle project is Prism Lab, a Java 25 Fabric demo mod, not the CLI.<br>
Version tags build a standalone JAR and portable ZIP with SHA-256 files in a draft GitHub release for manual publication.

<p align="center">
  <a href="https://github.com/Noisemaker111/modding-accelerator/releases/latest"><img alt="Download" src="https://img.shields.io/github/v/release/Noisemaker111/modding-accelerator?include_prereleases&label=download&color=5d9c3f"></a>
  <a href="https://github.com/Noisemaker111/modding-accelerator/actions/workflows/portable.yml"><img alt="Checks" src="https://github.com/Noisemaker111/modding-accelerator/actions/workflows/portable.yml/badge.svg?branch=main"></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-lightgrey"></a>
</p>

<p align="center">
  <a href="https://noisemaker111.github.io/modding-accelerator/"><b>Website</b></a> ·
  <a href="https://github.com/Noisemaker111/modding-accelerator/issues">Issues</a> ·
  <a href="https://noisemaker111.github.io/modding-accelerator/#porting">Porting map</a> ·
  <a href="SCOPE.md">What is supported</a>
</p>

## What it does

| | Goal | You run | Today |
|:-:|---|---|---|
| 1 | **Test a mod in seconds** | `fast` `data-check` `ui-check` `world-check` `mp-check` `server-check` | Works. Logic tests, the game's data and real-game checks all run with no game window |
| 2 | **Port a mod to any Minecraft version** | `port` `explain` | Works for Fabric and NeoForge, 1.14.4 to 26.3. Proven mods port with no hand edits, 1.21.1 → 26.3 included |
| 3 | **Make modpacks work together** | `pack-check` | Works for Forge, NeoForge, Fabric and Quilt packs on their real servers, with two proven repairs |
| 4 | **Change loader or platform** | `convert` | Works for Fabric ↔ NeoForge on one Minecraft version: real Fabric mods run on NeoForge, and NeoForge's 26.3 MDK runs on Fabric, with no hand edits. Swept on 35 real mods. Forge, Quilt and Paper are not converted yet |

## Quick start

1. Download the portable ZIP from the [latest release](https://github.com/Noisemaker111/modding-accelerator/releases/latest) and check it against its `.sha256`.
2. Extract it anywhere. It needs Java 17 or newer; on Windows, a machine with no Java downloads a checked runtime once.
3. Point it at your project:

```sh
./modaccel doctor --root my-mod          # what modaccel sees in your project
./modaccel fast --root my-mod            # compile and run your logic tests, no game
./modaccel ui-check --root my-mod        # your mod in the real client, no window on screen
./modaccel port --root my-mod --from 1.21.5 --to 1.21.6 --fix
./modaccel pack-check --pack my-pack.mrpack --fix
```

On Windows the command is `modaccel.cmd`; from any shell, `java -jar modaccel.jar`. `modaccel help` lists every command and `modaccel help COMMAND` explains one.

## Desktop app

The portable ZIP also holds Minecraft Modding Tools, a window over the same commands for people who don't use a terminal: `modaccel-desktop.exe` on Windows, `modaccel-desktop-linux` on Linux. Keep it in the extracted folder; it runs the launcher beside it, which finds or downloads Java as it does in a terminal.

- **Bench:** open the mod's folder (or drop it on the window). It sits on the bench with a tag saying what `doctor` found (loader, Minecraft version) and a slip of what has been inspected so far.
- **Checks:** an inspection sheet of stations, build, logic tests, game data, mixins, in the game, behaviour (`compile`, `fast`, `data-check`, `mixin-check`, `ui-check`), plus `world-check`, `mp-check`, `plugin-check` and `server-check`, with live output and Stop. A game check shows the game's own screenshot and is only tagged loaded until a behaviour check (`--assert`) passes; only then is it stamped works.
- **Change version:** preview a `port`, apply it, and undo it; or `convert` the mod between Fabric and NeoForge on the same Minecraft version, with undo.
- **Modpack:** `pack-check` on a CurseForge export, a Modrinth `.mrpack` or an instance folder (or drop it on the window), every mod laid out with the clashing ones flagged, then its repair and undo.
- **Measure:** find the running game, measure it, and read frame rate, a frame-time chart, the cost of each mod and where the time went, then open the full HTML report.

To build it from source, install Rust and run `cargo build --release` in `app/` (Linux also needs `libxkbcommon-dev` and `libwayland-dev`); `modaccel package` then adds it to the ZIP. It also renders without a window for checking: `app/target/release/modaccel-desktop --project . --screen checks --snap checks.png`, and `--script app/ui-tests/fast.json --realtime --out frames/` clicks through a real run, and `--list-scenes` names saved states to render.

## What works today

### 1 · Test a mod in seconds

- **Logic:** `fast` runs your JUnit tests without the game.
- **Data, no game:** `data-check` reads what the game loads for your mod (items, recipes, load errors) from the game's own code, with no game launched. `mixin-check` applies every mixin, with no game window.
- **The real game:** `ui-check`, `world-check` and `mp-check` (a dedicated server plus a client) run your mod in a hidden client. `eval` runs code inside the running game and `reload` swaps edited code into it.
- **Plugins:** `server-check` starts a real Paper server, any version from 1.8, and a test player follows your scenario. `plugin-check` is a faster check on a fake server.
- **Not yet:** game checks are proven on Windows and partly on Linux, not on macOS. `data-check` covers Fabric 26.x. The test player can't join modded servers.

### 2 · Port a mod to any Minecraft version

- **Fabric and NeoForge**, any version from 1.14.4 to 26.3, forward or back, routed through the versions most mods use. Every edit can be undone with `port --undo`.
- **One command does the whole move:** `port --fix` updates the build (loader, NeoGradle or Loom, Gradle, Java, dependencies). It renames what the game renamed, applies the rule book and recompiles until the mod builds. Then Mixin applies the mod's mixins, the mod's own tests run, and the jar is built. `--check COMMAND` runs the mod's own real-game check last. The port only counts as working once that check passes.
- **Renames come from the game itself.** For Mojang-named mods, `tools/GameDiff.java` reads Mojang's client jars and mappings for every release on the route, 26.x included. It links each release to the next from their own bytecode, and links NeoForge's own published classes the same way. Yarn-named Fabric mods use Fabric's yarn tables, because their names are yarn's.
- **The rule book** (`knowledge/fixes.json`) covers what a rename can't. Each rule carries a test `{error, before, after}` and its provenance: `hand` is written from a port's compiler errors against the game's own classes, and `third-party-mods` is the older set mined from other mods. New rules are never taken from other mods' code, and `--learn-from-mods` is off unless asked. `port` makes no AI or network-model calls; its rules are plain data.
- **Proven with no hand edits and checked in the real game:** AppleSkin 1.20.1 → 1.21.6 and 1.21.5 ↔ 1.21.6 (Fabric), AppleSkin 1.21.6 → 26.3 (NeoForge), Zombie Apocalypse Addon 1.21.1 → 1.21.4, 1.21.6 and 26.3, and Notes 1.21 → 1.21.6. Receipts are in `coverage/ledger.json`.
- **`explain`** shows each compiler error beside what that class looks like in both versions.
- **When a game member the mod reached through its access transformer turns private again**, the port writes the entry as the target game has it. **A mod's own subclass of a game class** follows the game's renames too.
- **Not yet:** Jade 1.21.1 → 1.21.6 stops at 30 named errors, from 98. They are in Jade's own renderer and font mixins, which 1.21.5–1.21.6's render rewrite replaced, and in fog, entity variants and two Jade API types. Forge, Quilt, Paper plugins and versions before 1.14.4 are not ported.

### 3 · Make modpacks work together

- **`pack-check`** takes a CurseForge export, a Modrinth `.mrpack`, or a CurseForge app, Prism/MultiMC or vanilla launcher instance. It runs the pack on its own Forge, NeoForge, Fabric or Quilt server, installed by that loader's official installer, for any version the loader publishes.
- **What it checks:** a new world is made, every block item is placed the way a player places it, and the same world is opened again with each block read back. Duplicate, missing and wrong-version mods, and mods for another game version or loader, are warnings until the server confirms or refutes them. When a start fails, the log, crash report, mixin configs and stack name the mod responsible.
- **`--fix`** applies a proven repair and starts the repaired pack's server beside the original. Your pack changes only if the repaired one passes where the original failed. Repairs so far: Biolith's saved climate list (Forge 1.20.1) and Soulslike Weaponry's pinned Projectile Damage (Forge 1.20.1).
- **Proven in the game** on real public packs: Forge 1.20.1, NeoForge 1.21.1, Fabric 1.21.1 and Quilt 1.21.1.
- **Sweep:** `python bench/pack_sweep.py run` checks 24 pinned public packs (`bench/pack-sweep/corpus.json`: CurseForge exports and `.mrpack`s, Forge, NeoForge, Fabric and Quilt, 1.16.5 to 26.3, 13 to over 400 mods) one at a time and ranks the gaps (`coverage/sweeps/pack.json`). A mod file CurseForge no longer serves is named as a problem and the rest of the pack is still checked.
- **Not yet:** checks run on a server, so client-only mods are left out of them. The probe's game code compiles for 1.16 to 26.x through per-version game layers. `tools/packcheck/CONTRIBUTING.md` explains how to add a loader, input, check or repair.

### Make a mod faster without changing what it does

- **Where the cost goes:** `perf --root my-mod` (or `perf` beside any running game or server) ranks every mod by CPU share, server ms per tick, render ms per frame, allocation, live heap of its own classes, lock wait and I/O, then splits each mod into features: event handlers, mixin handlers, blocks, entities, renderers, AI goals, screens. Sampled values are marked as estimates.
- **Exact numbers:** the capture writes timing probes for each mod's hottest methods; capture again with `--probes` for exact calls and milliseconds per tick.
- **Repeatable scenarios:** `perf-template --family mod-content --root my-mod` builds scenes and crowds of the mod's own blocks and entities, and runs them in the source client's own world.
- **Accept or reject a change:** `perf-optimize` takes a candidate diff you or your own agent wrote, measures baseline and candidate in alternating fresh launches (cold and warm), and accepts it only if every equivalence gate passes (behaviour assertions, exact or tolerance-bounded output, pixel-exact 1280x720 frames) and the speedup interval is above 1. It never writes code itself.
- **Not yet:** GPU time and VRAM, retained heap per mod, and mixin overwrites are not measured. Java 8/11 games are not supported.

See [PERFORMANCE.md](PERFORMANCE.md). `python3 bench/performance.py` compares externally collected paired runs against regression budgets ([capture contract](bench/performance.md)).

### 4 · Change loader or platform

- **`convert --to neoforge`** (or `--to fabric`) moves a mod's source to the other loader on the same Minecraft version: the build (Loom ↔ ModDevGradle, with the newest loader releases for that version), the metadata (`fabric.mod.json` ↔ `META-INF/neoforge.mods.toml`), the entrypoints, and the code through the rules in `knowledge/loaders.json`: loader calls, events, key mappings, registration timing, flammability, creative tabs, loot tables, Mod Menu's config screen, Fabric data generation, access wideners ↔ access transformers (field types read from Mojang's own data), and dependencies on another mod (moved to that mod's build for the other loader when its own Maven repository publishes it). It reads NeoForge's MDK layouts (ModDevGradle or NeoGradle, metadata in `src/main/templates`) and moves the Gradle wrapper when the newest loader plugin needs it. It compiles the result; `convert --undo` restores every file.
- **Our own rules, no AI.** Each rule is written here with a before/after test that `self-test` runs both ways, and names the classes and members it uses; `python bench/loader_api.py verify` finds every one in Fabric's, NeoForge's and Mojang's own published jars (`coverage/loader/api.json`). Nothing is taken from other mods' code. What has no rule is listed with its file and line, never dropped.
- **Proven in the game with no hand edits:** Auto Omen (Fabric, 26.3) on NeoForge passes its own in-game raid self test, 23 of 23 checks, as it does on Fabric. Pride Flags (Fabric, 26.1.2) on NeoForge registers its 13 blocks and 26 items, keeps their flammability, adds its loot to village chests, loads its 48 recipes, and renders its flags from the models its converted data generation writes (189 files, the same as Fabric's but for vanilla's `random_sequence` in block loot).
- **Mods written for NeoForge first** go to Fabric through the NeoForge bridge (`knowledge/bridges/neoforge-on-fabric`, Minecraft 26.1 and later): NeoForge's registration, event bus, mod container, config file and play payloads, and its commonest lifecycle events, written on Fabric's API and copied into the mod. The NeoForge MDK for 26.3 (ModDevGradle and NeoGradle) converts with no hand edits, loads on Fabric in a hidden client and registers the same entries as its NeoForge build.
- **Sweep:** `python bench/convert_sweep.py run` converts 35 pinned real mods (`bench/convert-sweep/corpus.json`) and ranks what is missing by loader concept (`coverage/sweeps/convert.json`).
- **Not yet:** Forge, Quilt, and mods ↔ Paper plugins. NeoForge → Fabric before 26.1 builds with Loom on Mojang's names, but the NeoForge bridge is not written for those versions yet. No rules yet for capabilities and transfer, NeoForge's extension methods on game classes, HUD layers, renderers, data attachments, client commands, datagen providers beyond models, loot, recipes and advancements, or Fabric networking on NeoForge.

## What counts as working

A check that only shows the mod loaded is not proof it works. Proof is a check on the mod's own code or state: `--assert`, `--expect-value`, or a server scenario's expect lines. Machine-readable port receipts live in `coverage/ledger.json`; status and plans belong in [PRs](https://github.com/Noisemaker111/modding-accelerator/pulls) and [issues](https://github.com/Noisemaker111/modding-accelerator/issues).

## Limits

- **Source only.** A compiled mod JAR can't be ported. Compiled mods are only read when a modpack is checked.
- **Deterministic ports.** A port uses the game's own jars and mappings, NeoForge's own artifacts and the tested rule book. A change no rule covers yet is named with the update that caused it, so the next rule can be written.

## More

- [Issues](https://github.com/Noisemaker111/modding-accelerator/issues) and [PRs](https://github.com/Noisemaker111/modding-accelerator/pulls): plans, status and findings.
- [`SCOPE.md`](SCOPE.md): which kinds of projects are supported, and how far.
- [`AGENTS.md`](AGENTS.md): the rules for working in this repo.
- [`bench/README.md`](bench/README.md): recording receipts and comparing measurements.
- [Releases](https://github.com/Noisemaker111/modding-accelerator/releases): what changed in each version.
