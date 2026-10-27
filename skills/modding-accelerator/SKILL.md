---
name: modding-accelerator
description: Fast checks for Minecraft Fabric, NeoForge and Forge mods. Use when changing mod code: run logic, screen-pixel, block-behaviour, server or multiplayer checks in seconds instead of launching a client and clicking, and use port -Check/-Log/-Fix when moving a mod to a new Minecraft version. Includes measured timings and the rules for trusting a result.
---

# Modding Accelerator

Save a file, run `.\modaccel`, and it decides the cheapest check that covers the change.

Times below come from `.\modaccel bench` on a Ryzen 9 3900X, Minecraft 26.3 + Fabric. They are medians
of 5 runs unless noted.

## Commands

| Command | Covers | Time |
|---|---|---|
| `.\modaccel` | lint (registrations, assets, lang, mixin audit) + logic, auto-routes by what changed in `src/` | **0.5-0.9s** |
| `.\modaccel fast` | the same lane without routing | 1-3s |
| `.\modaccel ui` | a screenshot of your screen, plus a real button click | **1.5s** |
| `.\modaccel node` | a block entity in a live world, probe value asserted against the expected one | **2.1s** |
| `.\modaccel world` | new registrations, mixins, blockstates/item models/lang in a fresh headless server | **15s** |
| `.\modaccel look` | fresh client, world, 4 screenshots | **53s** |
| `.\modaccel restart` | stop, compile, start after a schema, field or method change | 49s on NeoForge |
| `.\modaccel bench -Tier ui -Count 5` | re-measure a tier, appends to `.runs/bench.jsonl` | - |
| `.\modaccel diff -Old <jar> -New <jar>` | references your code makes that the new jar lacks | 1-2s |
| `.\modaccel port -New <jar> [-Apply]` | that diff + knowledge notes + `PORT-REPORT.md`; `-Apply` fixes the safe renames | 2-3s |
| `.\modaccel port -Api <old jar> <new jar>` | what Minecraft itself changed between two versions (removed/moved classes, members, signatures, renames) | 1.9s |
| `.\modaccel port -Check <repo> [-To <mc>]` | can this repo build at the target: JDK, loader, version pins, `gradlew help` proof, source references to removed symbols | 9-89s |
| `.\modaccel port -Log <log> [-To <mc>]` | which compiler errors are known, which replacement or written recipe applies | - |
| `.\modaccel port -Fix -Log <log>` | apply `knowledge\fixes.json`, rebuild, repeat until green (`-Task`, `-Max`, `-DryRun`, `-Repo`) | 3-8s |
| `.\modaccel scenario -Steps "..."` | scripted steps in the warm world: place, wait, assert, screen, click, command, capture, settle | 0.2-3s |
| `.\modaccel suite [-File <cases.txt>]` | every named case in a file, one warm client | seconds |
| `.\modaccel mp -Steps "..." [-Cold]` | dedicated server + hidden client, asserting on what the server sent; `-Cold` refuses a server older than the sources | 4s warm |
| `.\modaccel status` | port, pid and age of every lab process | instant |
| `.\modaccel -Version 1.21.11 ui` | the same check on another Minecraft node | 1-5s |

`.\modaccel ui` and `.\modaccel node` need a hidden Minecraft client (started once, ~32s cold). `.\modaccel` never
touches Minecraft.

## The bridge (a client that belongs to someone else's mod)

`install.ps1` drops a small bridge jar into the repo: `.modaccel\bridge.jar` for NeoForge (added
through `-Dfml.modFolders`) and `.modaccel\bridge-forge.jar` for Forge (copied into `mods\` on each
run). It answers `root`, `screenshot` and `ui <Screen>` without the host mod changing a line.
The Fabric/NeoForge bridge is gated to Minecraft 26.x; on any other version the installer refuses it
with a message and `ui`, `node`, `look`, `suite` and `mp` stay unavailable. The Forge bridge comes
from `tools\capture-launch.ps1` once per repo and replays `.modaccel\forge.args`; there is no hot
reload on that lane (a code edit means a new client).

## Rules

- Run the cheapest check that can actually catch the mistake, then say which one you ran.
- `.\modaccel ui` prints PNG paths, and only PNGs this run wrote. A human or the agent looks at the
  picture; there is no pixel comparison to maintain.
- No rendering claim without a screenshot; no "fixed" without a check this session.
- New block/item id, a new mixin target or a changed `@Shadow`: `.\modaccel world`, once.
- `bench\fault-matrix.md` records seven injected faults and which tier caught each, plus the gaps
  that remain. Read it before trusting a green line as proof.

## Limits

- Pixels and behaviour need a Minecraft process (hidden client, or headless server). Nothing
  else can run mod code.
- Hot reload covers method bodies, added fields/methods and screen code on Fabric, NeoForge and
  1.21.11. A new registered id, a changed class hierarchy, a new mixin target or `fabric.mod.json`
  needs the boot; `restart` does stop -> compile -> start in one command.
- A warm tier cannot see a fault that was already in the tree when its process started, and a
  render exception kills the shared warm client.
- One lab client per machine; a second repo refuses to drive it.
- Verified on Minecraft 26.3 + Fabric, 1.21.11, NeoForge 1.21.1 and Forge 1.20.1. Other Fabric
  versions need `gradle.properties`, the Loom plugin id and mappings changed; NeoForge and Forge
  are their own lanes.

What works today: `README.md`. What's next: `ROADMAP.md`.
