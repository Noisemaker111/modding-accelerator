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
| 2 | **Port a mod to any Minecraft version** | `port` `explain` | Works for Fabric and NeoForge, 1.14.4 to 26.3. Small jumps need no hand edits |
| 3 | **Make modpacks work together** | `pack-check` | Works for Forge 1.20.1 packs, with one proven repair so far |
| 4 | **Change loader or platform** | | Planned. Nothing is built yet |

## Quick start

1. Download the portable ZIP from the [latest release](https://github.com/Noisemaker111/modding-accelerator/releases/latest) and check it against its `.sha256`.
2. Extract it anywhere. It needs Java 17 or newer; on Windows, a machine with no Java downloads a checked runtime once.
3. Point it at your project:

```sh
./modaccel doctor --root my-mod          # what modaccel sees in your project
./modaccel fast --root my-mod            # compile and run your logic tests, no game
./modaccel ui-check --root my-mod        # your mod in the real client, no window on screen
./modaccel port --root my-mod --from 1.21.5 --to 1.21.6 --fix
./modaccel pack-check --pack my-pack.zip --fix
```

On Windows the command is `modaccel.cmd`; from any shell, `java -jar modaccel.jar`. `modaccel help` lists every command and `modaccel help COMMAND` explains one.

## What works today

### 1 · Test a mod in seconds

- **Logic:** `fast` runs your JUnit tests without the game.
- **Data, no game:** `data-check` reads what the game loads for your mod (items, recipes, load errors) from the game's own code, with no game launched. `mixin-check` applies every mixin, with no game window.
- **The real game:** `ui-check`, `world-check` and `mp-check` (a dedicated server plus a client) run your mod in a hidden client. `eval` runs code inside the running game and `reload` swaps edited code into it.
- **Plugins:** `server-check` starts a real Paper server, any version from 1.8, and a test player follows your scenario. `plugin-check` is a faster check on a fake server.
- **Not yet:** game checks are proven on Windows and partly on Linux, not on macOS. `data-check` covers Fabric 26.x. The test player can't join modded servers.

### 2 · Port a mod to any Minecraft version

- **Fabric and NeoForge**, any version from 1.14.4 to 26.3, forward or back, routed through the versions most mods use. Every edit can be undone with `port --undo`.
- **Proven with no hand edits** on F3Nope 1.21.5 ↔ 1.21.6 and Zombie Apocalypse Addon 1.21.1 → 1.21.4.
- **`explain`** shows each compiler error beside what that class looks like in both versions.
- **Not yet:** big jumps stop with named errors (AppleSkin 1.20.1 → 1.21.6 stops at 11). Forge, Quilt, Paper plugins and versions before 1.14.4 are not ported.

### 3 · Make modpacks work together

- **`pack-check`** takes a CurseForge export or an instance folder and runs it on its own Forge server. It finds duplicate and missing mods, blocks that place as another block, and worlds that open once but not again.
- **`--fix`** applies a proven repair, then starts the repaired pack beside the original to show it working.
- **Not yet:** Forge 1.20.1 only in the game, and one known repair. Other loaders get the checks that need no game.

### 4 · Change loader or platform

Moving a project between Fabric, NeoForge, Forge, Quilt and Paper is the fourth goal. There is no conversion command yet; `coverage/loader/api.json` records loader API counterparts mined from multi-loader source.

## What counts as working

A check that only shows the mod loaded is not proof it works. Proof is a check on the mod's own code or state: `--assert`, `--expect-value`, or a server scenario's expect lines. Machine-readable port receipts live in `coverage/ledger.json`; status and plans belong in [PRs](https://github.com/Noisemaker111/modding-accelerator/pulls) and [issues](https://github.com/Noisemaker111/modding-accelerator/issues).

## Limits

- **Source only.** A compiled mod JAR can't be ported. Compiled mods are only read when a modpack is checked.
- **No AI guessing.** Ports use the game's own name lists, rules learned from real multi-version mods, and checked repairs. What's left is listed, not guessed.

## More

- [Issues](https://github.com/Noisemaker111/modding-accelerator/issues) and [PRs](https://github.com/Noisemaker111/modding-accelerator/pulls): plans, status and findings.
- [`SCOPE.md`](SCOPE.md): which kinds of projects are supported, and how far.
- [`AGENTS.md`](AGENTS.md): the rules for working in this repo.
- [`bench/README.md`](bench/README.md): recording receipts and comparing measurements.
- [Releases](https://github.com/Noisemaker111/modding-accelerator/releases): what changed in each version.
