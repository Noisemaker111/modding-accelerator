# Modding Accelerator

**Test a Minecraft mod in seconds, port it to any version, and make modpacks work together, without opening the game or diagnosing problems by hand.**

`modaccel` is one command with four jobs:

1. **Test it fast.** Your logic tests run in seconds without the game. When the game is needed, a hidden client or a
   real server runs your mod or plugin, and a check asserts on the mod's own state, so you never launch Minecraft
   and click through it yourself.
2. **Port it.** Point it at your Gradle project and name a Minecraft version. It rewrites what changed in the game
   between the two versions, builds the result, and lists anything it couldn't fix, with the game update that
   caused it. Every edit can be undone.
3. **Make modpacks compatible.** Identify and resolve conflicts among installed mods and configurations, and check
   the affected behavior on the pack's real server. `pack-check` does this for Forge packs today.
4. **Change loader.** Move a project between Fabric, NeoForge, Forge, Quilt and Paper. Planned; nothing is built yet.

Beta `0.1.0-beta.3` · [download](https://github.com/Noisemaker111/modding-accelerator/releases/latest) ·
[roadmap, numbers and porting map](https://noisemaker111.github.io/modding-accelerator/)

## Quick start

Download the portable ZIP from the release, check it against its `.sha256`, extract it, then:

```sh
./modaccel doctor --root /path/to/mod                     # what modaccel sees in your project
./modaccel fast --root /path/to/mod                       # compile and run logic tests, no game
./modaccel ui-check --root /path/to/mod                   # real client, 1280x720 capture
./modaccel server-check --root /path/to/plugin --file my.scenario
./modaccel port --root /path/to/mod --from 1.21.5 --to 1.21.6 --fix
./modaccel pack-check --pack /path/to/pack                # a modpack on its real server
```

Windows: `modaccel.cmd`. Any shell: `java -jar modaccel.jar`. Needs Java 17+; on Windows a machine with none
downloads a checked runtime once. `modaccel help` lists every command; `modaccel help COMMAND` explains one.

## What works today

| Goal | Job | Works | Not yet |
|---|---|---|---|
| 1 Test | Mod logic | `fast` runs your JUnit tests without the game | |
| 1 Test | Mod data, no game | `data-check`: what the game loads for the mod (items, recipes, load errors) from the game's own code, with no game launched. `mixin-check` applies every mixin, no game window | `data-check` covers Fabric 26.x |
| 1 Test | Mod in the real game | `ui-check`, `world-check`, `mp-check` (dedicated server plus client), `eval` (run code inside the game), `reload` (swap edited code into a running game), all with no game window | Proven on Windows; on Linux in part; not on macOS |
| 1 Test | Plugin on a server | `server-check`: a real Paper server with a test player (`serverFakeBot`) following your scenario, any Paper version from 1.8. `plugin-check`: a fast fake-server check | The test player can't join modded servers. `plugin-check` covers Paper 1.21 and newer |
| 2 Port | Port a Fabric or NeoForge mod | Any version from 1.14.4 to 26.3, routed through the versions most mods use. Proven with no hand edits on F3Nope 1.21.5 ↔ 1.21.6 and Zombie Apocalypse Addon 1.21.1 → 1.21.4 | Big jumps stop with named errors (AppleSkin 1.20.1 → 1.21.6 stops at 11). Forge, Quilt, Paper plugins, and versions before 1.14.4 |
| 2 Port | Explain a port's errors | `explain`: each compiler error beside what that class looks like in both versions | |
| 3 Modpacks | Diagnose and repair a modpack | `pack-check`: a CurseForge export or instance folder on its own Forge server. Finds duplicate and missing mods, blocks that place as another block, and worlds that open once but not again; `--fix` applies a proven repair and checks it | Forge 1.20.1, one known repair so far. Other loaders get the checks without the game |
| 4 Loaders | Change loader (e.g. Fabric → NeoForge, Forge → Paper) | | Not built |

A check that only shows the mod loaded is not proof it works. Proof is a check on the mod's own code or state:
`--assert`, `--expect-value`, or a server scenario's expect lines.

## Limits

- **Source only.** A compiled mod JAR can't be ported.
- **No AI guessing.** Ports use the game's own name lists, rules learned from real multi-version mods, and checked
  repairs. What's left is listed, not guessed.

## Where things are

- [`ROADMAP.md`](ROADMAP.md): where each of the four goals stands, every step with its dates, every number with its target. Generated from [`coverage/roadmap.json`](coverage/roadmap.json) and [`coverage/measures.json`](coverage/measures.json). The same as a [page](https://noisemaker111.github.io/modding-accelerator/), with the porting map and every port run.
- [`SCOPE.md`](SCOPE.md): which kinds of projects are supported, and how far.
- [`AGENTS.md`](AGENTS.md): the rules for working in this repo.
- [Porting map](https://noisemaker111.github.io/modding-accelerator/#porting): every release with its route and what was really run. Data in [`coverage/changes/`](coverage/changes/), made by [`tools/ChangeIndex.java`](tools/ChangeIndex.java); runs in [`coverage/ledger.json`](coverage/ledger.json).
- [`bench/alternatives.md`](bench/alternatives.md): how this compares with Loom, GameTest, HeadlessMC and others.
- [Releases](https://github.com/Noisemaker111/modding-accelerator/releases): what changed in each version.
