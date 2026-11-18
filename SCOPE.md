# What works where

mmt counts something as working only when the game shows it: the mod's own state asserted in a real client, a plugin
scenario on a real Paper server, or a modpack's world made and reopened on its own server. A build or a load is a step
on the way, not a result. This file says which platform has reached what, and how a new one gets in.

## What works, per platform

"Yes" means it has passed on a real project nobody edited for us, with a receipt (`coverage/ledger.json`, a sweep in
`coverage/sweeps/`, or a kept run in `coverage/perf-runs.json`).

| | Fabric | NeoForge | Forge | Quilt | Paper plugin |
|---|---|---|---|---|---|
| Build and logic tests (`compile`, `fast`) | Yes | Yes | Yes | Through Fabric | Yes (AgentCraft, all 17 tests) |
| Game data and mixins, no game (`data-check`, `mixin-check`) | Yes (`data-check`: 26.x) | `mixin-check` | No | No | Not applicable |
| Behaviour in a hidden client (`ui-check`, `world-check`) | Yes | Yes | Loads only | No | Not applicable |
| Dedicated server plus client or test player (`mp-check`, `server-check`) | Partly | Partly | No | No | Yes: real Paper 1.8 and up, with a test player |
| Port between versions (`port`) | Yes, 1.14.4 to 26.3 | Yes, 1.14.4 to 26.3 | No | No | No: `port` names the edits instead |
| Switch loader (`convert`) | To NeoForge | To Fabric (26.1+ and 1.21.1) | No | No | No |
| Modpacks on their own server (`pack-check`) | Yes | Yes | Yes | Yes | Not applicable |
| Performance (`perf`, `perf-optimize`) | Yes | Yes | Not yet proven | No | No |

Multi-version builds (Stonecutter, such as Diorama) work through their version nodes: build, tests, game checks and
port each run on one node. Proxy plugins (Velocity, BungeeCord) and standalone datapacks have no commands.

Operating systems: the CLI and its offline checks pass on Windows, Linux and macOS in CI. Game checks are proven on
Windows and partly on Linux, not on macOS.

## Current numbers

| What | Corpus | Result | Source |
|---|---|---|---|
| Ports proven in the game, no hand edits | 3 mods, 7 routes | All pass their own in-game check | `coverage/ledger.json` |
| Port sweep | 46 mods, 126 routes | 9 build, 7 load, 1 passes its behaviour check | `bench/sweep/results/after2.json` |
| Convert sweep | 35 mods | 28 not refused, 14 compile and load in the game, 10 behave the same as the original (fingerprint), 9 convert back | `coverage/sweeps/convert.json` |
| Pack sweep | 24 packs, 1.16.5 to 26.3 | 21 pass as shipped, 24 with repairs | `coverage/sweeps/pack.json` |
| Accepted speedups, exact gates | 4 mods | Cyclic 15.7×, Simple Storage Network 5–6×, Diorama 1.6–2.2×, Pipez 1.2–1.3× | `coverage/perf-runs.json` |

## Who it is for

- People who write mods or plugins from source. Porting and converting need the source; a compiled jar is only read
  when a modpack is checked.
- Modpack users and makers who need their installed mods to work together.
- Fabric and NeoForge come first, then Forge (older modpacks), then Paper (servers). Quilt rides on Fabric.

## How a platform or feature gets in

1. A real project someone uses needs it.
2. It is proven in the game on that project, and a receipt is recorded.
3. One fixture from it joins the checks CI runs (`bench/run_tests.py`, or `integration.yml` when it needs a server).

Claims follow the table: the README says what has passed, never just "supports X". Loader switches and version ports
are separate claims. A move between a mod and a Paper plugin can only carry server-side code (events, commands,
scheduler, config); client code, mixins and new blocks or items cannot exist on a plugin server, so such a move names
them and stops. `convert` does not move mods to or from Paper yet.

## When someone brings a new project

1. **Bench it** with the `bench-new-mod` skill: its own workflow, before/after numbers, blockers and versions.
2. **Place it** in the table above: platform, side (client, server or both), and the furthest row it passes.
3. **Decide:** it joins the fixtures if it covers a new kind of build; a failing row gets the smallest fix on a branch
   and an issue naming the blocker; a new platform follows the three steps above; anything out of scope is said plainly.
