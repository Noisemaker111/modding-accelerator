<p align="center"><img src="docs/logo.svg" width="64" height="64" alt=""></p>

<h1 align="center">Minecraft Modding Tools</h1>

<p align="center">
  <b>Prove a Minecraft mod works in the game, then port it, convert it, fit it into a modpack, or make it faster.</b><br>
  One command, <code>mmt</code>, and a desktop app over the same commands.
</p>

<p align="center">
  <a href="https://github.com/Noisemaker111/modding-accelerator/releases/latest"><img alt="Download" src="https://img.shields.io/github/v/release/Noisemaker111/modding-accelerator?include_prereleases&label=download&color=5d9c3f"></a>
  <a href="https://github.com/Noisemaker111/modding-accelerator/actions/workflows/portable.yml"><img alt="Checks" src="https://github.com/Noisemaker111/modding-accelerator/actions/workflows/portable.yml/badge.svg?branch=main"></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-lightgrey"></a>
</p>

<p align="center">
  <a href="https://noisemaker111.github.io/modding-accelerator/"><b>Website</b></a> ·
  <a href="https://noisemaker111.github.io/modding-accelerator/#porting">Porting map</a> ·
  <a href="SCOPE.md">What works where</a> ·
  <a href="https://github.com/Noisemaker111/modding-accelerator/issues">Issues</a>
</p>

**The foundation: a mod counts as working only when the game shows it working.** A build that compiles, or a client
that loads the mod, proves nothing about what the mod does. Every command below ends in the real game, hidden and muted:
the mod's own state asserted in a client (`--assert`, `--expect-value`), a plugin scenario on a real Paper server, a
modpack's world made, filled and reopened on its own server, or a performance change measured against the unchanged mod
with its output compared exactly.

## What it does

| Goal | You run | Where it stands |
|---|---|---|
| **Test** a mod in the real game, with no window on screen | `fast` `data-check` `mixin-check` `ui-check` `world-check` `mp-check` `server-check` | Works for Fabric and NeoForge mods and Paper plugins; Forge mods load in a client |
| **Port** a mod to another Minecraft version, finishing on its own | `port --fix` `explain` | Fabric and NeoForge, 1.14.4 to 26.3. 7 routes of 3 mods proven in the game with no hand edits |
| **Switch loader** | `convert` | Fabric ↔ NeoForge on one Minecraft version. Two Fabric mods and NeoForge's MDKs proven in the game |
| **Make a modpack work**, with repairs | `pack-check --fix` | Forge, NeoForge, Fabric and Quilt packs on their real servers. 24 of 24 corpus packs pass, 3 of them repaired |
| **Make a mod dramatically faster**, with nothing changed in what it does | `perf` `perf-optimize` | Accepted changes so far: Cyclic's auto-crafter 15.7×, Simple Storage Network 5–6×, Diorama 1.6–2.2× |
| **Desktop app** for people who don't use a terminal | `mmt-desktop` | Windows and Linux, in the portable ZIP |

**Our own rules, no AI.** Mappings come from the game and the loaders themselves: Mojang's client jars and mappings
for every release on a route, NeoForge's and Fabric's own published jars. Rules for what a rename can't cover are
written here, each with a before/after test, and never taken from other people's mods. The tool makes no AI or
network-model calls; its rules are plain data in `knowledge/`.

## Quick start

1. Download the portable ZIP from the [latest release](https://github.com/Noisemaker111/modding-accelerator/releases/latest) and check it against its `.sha256`.
2. Extract it anywhere. It needs Java 17 or newer; on Windows, a machine with no Java downloads a checked runtime once.
3. Point it at your mod's source folder (the one with `gradlew`):

```sh
./mmt doctor --root my-mod          # what mmt sees in your project
./mmt fast --root my-mod            # compile and run your logic tests, no game
./mmt ui-check --root my-mod        # your mod in the real client, no window on screen
./mmt port --root my-mod --from 1.21.5 --to 1.21.6 --fix
./mmt convert --root my-mod --to neoforge
./mmt pack-check --pack my-pack.mrpack --fix
./mmt perf --root my-mod
```

On Windows the command is `mmt.cmd`; from any shell, `java -jar mmt.jar`. `mmt help` lists every command and
`mmt help COMMAND` explains one. Games start muted; `mmt preferences --background on` also keeps their window hidden.

The tool was called `modaccel` before. The `modaccel` and `modaccel.cmd` launchers still work: they print a one-line
note and run `mmt`. On its first run `mmt` copies `~/.modaccel` into `~/.mmt` (your preferences byte for byte; big
cache files are hard-linked where the disk allows) and keeps the old folder. A project's `.modaccel/` folder becomes
`.mmt/`, and `MODACCEL_*` environment variables are still read when the `MMT_*` one is not set.

## Test

- **Logic:** `fast` runs your JUnit tests without the game.
- **Data with no game:** `data-check` reads what the game loads for your mod (items, recipes, load errors) from the
  game's own code. `mixin-check` lets Mixin itself apply every mixin.
- **The real game:** `ui-check`, `world-check` and `mp-check` (a dedicated server plus a client) run your mod in a
  hidden client and pass only when your assertion on the mod's own state passes. `eval` runs code in the running game;
  `reload` swaps edited code into it.
- **Plugins:** `server-check` starts a real Paper server, any version from 1.8, and a test player follows your scenario.
  `plugin-check` is a quicker check on a fake server and proves less.

## Port

`port --fix` updates the build (loader, Loom or NeoGradle/ModDevGradle, Gradle, Java, dependencies), renames what the
game renamed, applies the rule book, and recompiles until the mod builds. Then Mixin applies the mod's mixins, its own
tests run and the jar is built; `--check COMMAND` runs the mod's own in-game check last, and the port counts only when
it passes. Every edit can be undone with `port --undo`. `explain` shows each compiler error beside what that class
looks like in both versions.

- **Proven in the game with no hand edits:** AppleSkin 1.20.1 → 1.21.6 and 1.21.5 → 1.21.6 (Fabric), AppleSkin
  1.21.6 → 26.3 (NeoForge), Zombie Apocalypse Addon 1.21.1 → 1.21.4, 1.21.6 and 26.3, and Notes 1.21 → 1.21.6
  (NeoForge).
  Receipts: `coverage/ledger.json`.
- **Across a corpus:** `bench/sweep.py` ports 46 pinned open-source mods over 126 routes and starts each one that
  builds in a hidden game. Latest sweep: 9 of 126 build, 7 load in the game, 1 passes its in-game behaviour check
  (`bench/sweep/results/after2.json`). The rest stop at a named compiler error, which is where the next rule goes.

## Switch loader

`convert --to neoforge` (or `--to fabric`) moves a mod's build, metadata, entrypoints and code to the other loader on
the same Minecraft version, through the rules in `knowledge/loaders.json`; `python bench/loader_api.py verify` finds
every class and member a rule names in the loaders' and Mojang's own jars. What has no rule is listed with its file
and line, never dropped. `convert --undo` restores every file.

- **Proven in the game with no hand edits:** Auto Omen (Fabric 26.3) on NeoForge passes its own in-game raid self test,
  23 of 23 checks. Pride Flags (Fabric 26.1.2) on NeoForge registers its 13 blocks and 26 items and renders its flags.
  NeoForge's 26.3 and 1.21.1 MDKs run on Fabric through the NeoForge bridge, register what their NeoForge builds do,
  and convert back.
- **Across a corpus** of 35 pinned mods (`coverage/sweeps/convert.json`): 28 convert without a refusal, 5 compile,
  5 load in the game with the same behaviour as the original, and 5 convert back and compile.

## Make a modpack work

`pack-check` takes a CurseForge export, a Modrinth `.mrpack`, or a CurseForge app, Prism/MultiMC or vanilla launcher
instance, and runs it on its own Forge, NeoForge, Fabric or Quilt server, installed by that loader's official
installer. A new world is made, every block item is placed as a player places it, and the world is opened again with
each block read back. When a start fails, the log, crash report, mixin configs and stack name the mod responsible.
`--focus MODID` starts only that mod and what it requires, to show one fault quickly.

`--fix` applies a proven repair and starts the repaired pack beside the original; your pack changes only if the
repaired one passes where the original failed. The repair rules (`knowledge/pack-fixes.json`) cover files a host no
longer serves, missing and pinned libraries, server-optional mods that stop a server, and known faults in Biolith,
Soulslike Weaponry and Ender IO; each names the evidence it came from.

Across 24 pinned public packs (1.16.5 to 26.3, 13 to over 400 mods; `coverage/sweeps/pack.json`): 21 start as shipped
with the world made and reopened, and all 24 pass with repairs.

## Make a mod faster

`perf` ranks every mod by CPU share, server ms per tick, render ms per frame, allocation, lock wait and I/O, and splits
each mod into features (event handlers, mixins, blocks, entities, renderers). `perf-optimize` takes a candidate change
you or your agent wrote, measures the unchanged and changed mod in alternating fresh launches, and accepts it only when
every equivalence gate passes (behaviour assertions, exact output, pixel-exact 1280x720 frames) and the speedup's
interval is above 1. It never writes code itself. The aim is 10× with nothing degraded.

Accepted so far (`coverage/perf-runs.json`, summarised by `python bench/perf_sessions.py`): Cyclic's auto-crafter
15.7× cold and 15.9× warm, Simple Storage Network 5.1× cold and 6.0× warm, Diorama 1.6× cold and up to 2.2× warm,
Pipez 1.2–1.3×. Each accepted change became a reusable pattern in `knowledge/perf-patterns.json`. See
[PERFORMANCE.md](PERFORMANCE.md).

## Desktop app

The portable ZIP holds the app: `mmt-desktop.exe` on Windows, `mmt-desktop-linux` on Linux. Keep it in the extracted
folder; it runs the `mmt` launcher beside it. Open a mod's folder (or drop it on the window) to run the checks with
live output, preview, apply and undo a port or a loader switch, check and repair a modpack, and measure a running game.
To build it, install Rust and run `cargo build --release` in `app/`; `mmt package` then adds it to the ZIP.

## Limits

- **Source only.** A compiled mod JAR can't be ported or converted; compiled mods are read only when a modpack is checked.
- **Platforms.** Game checks are proven on Windows and partly on Linux, not on macOS. Forge, Quilt and Paper plugins
  are not ported or converted. Java 8/11 games are not measured by `perf`. Details per platform: [SCOPE.md](SCOPE.md).

## More

- [`SCOPE.md`](SCOPE.md): what works on each platform.
- [`AGENTS.md`](AGENTS.md): how to work in this repo. [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md): setup traps.
- [`bench/README.md`](bench/README.md): recording receipts and comparing measurements.
- Plans, status and findings live in [issues](https://github.com/Noisemaker111/modding-accelerator/issues) and
  [pull requests](https://github.com/Noisemaker111/modding-accelerator/pulls).
