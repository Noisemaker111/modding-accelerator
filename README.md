# Modding Accelerator

**Move your Minecraft mod or plugin to another version, and know it works, without launching Minecraft yourself.**

`modaccel` does two jobs for a mod or plugin you have the source for:

1. **Port it.** Point it at your Gradle project and name a Minecraft version. It rewrites what changed in the game
   between the two versions, builds the result, and lists anything it couldn't fix, with the game update that
   caused it. Every edit can be undone.
2. **Prove it works.** It runs your mod in the real game, and your plugin on a real server, with a test player that
   types commands and checks what happened. Your logic tests run in seconds without the game.

Beta `0.1.0-beta.1` · [download](https://github.com/Noisemaker111/modding-accelerator/releases/latest) ·
[porting map](https://noisemaker111.github.io/modding-accelerator/porting-map.html) ·
[bench](https://noisemaker111.github.io/modding-accelerator/)

## Quick start

Download the portable ZIP from the release, check it against its `.sha256`, extract it, then:

```sh
./modaccel doctor --root /path/to/mod                     # what modaccel sees in your project
./modaccel port --root /path/to/mod --from 1.21.5 --to 1.21.6 --fix
./modaccel fast --root /path/to/mod                       # compile and run logic tests, no game
./modaccel ui-check --root /path/to/mod                   # real client, 1280x720 capture
./modaccel server-check --root /path/to/plugin --file my.scenario
```

Windows: `modaccel.cmd`. Any shell: `java -jar modaccel.jar`. Needs Java 17+; on Windows a machine with none
downloads a checked runtime once. `modaccel help` lists every command; `modaccel help COMMAND` explains one.

## What works today

| Job | Works | Not yet |
|---|---|---|
| Port a Fabric or NeoForge mod | Any version from 1.14.4 to 26.3, routed through the versions most mods use. Proven with no hand edits on F3Nope 1.21.5 ↔ 1.21.6 and Zombie Apocalypse Addon 1.21.1 → 1.21.4 | Big jumps stop with named errors (AppleSkin NeoForge 1.21.6 → 26.3 needed one hand-written file). Forge, Quilt, and versions before 1.14.4 |
| Change loader (Fabric → NeoForge) | | Not built |
| Mod logic | `fast` runs your JUnit tests without the game | |
| Mod in the real game | `ui-check`, `world-check`, `mp-check` (dedicated server plus client), `eval` (run code inside the game), `reload` (swap edited code into a running game), `mixin-check` (applies every mixin, no game window) | Proven on Windows only |
| Plugin on a server | `server-check`: a real Paper server with a test player (`serverFakeBot`) following your scenario, any Paper version from 1.8. `plugin-check`: a fast fake-server check | The test player can't join modded servers. `plugin-check` covers Paper 1.21 and newer |
| Explain a port's errors | `explain`: each compiler error beside what that class looks like in both versions | |

A check that only shows the mod loaded is not proof it works. Proof is a check on the mod's own code or state:
`--assert`, `--expect-value`, or a server scenario's expect lines.

## Limits

- **Source only.** A compiled mod JAR can't be ported.
- **No AI guessing.** Ports use the game's own name lists, rules learned from real multi-version mods, and checked
  repairs. What's left is listed, not guessed.

## Where things are

- [`ROADMAP.md`](ROADMAP.md): what's next, in order.
- [`SCOPE.md`](SCOPE.md): which kinds of projects are supported, and how far.
- [Porting map](https://noisemaker111.github.io/modding-accelerator/porting-map.html): every release with its route and what was really run. Data in [`coverage/changes/`](coverage/changes/), made by [`tools/ChangeIndex.java`](tools/ChangeIndex.java).
- [Bench](https://noisemaker111.github.io/modding-accelerator/): every port run, from [`coverage/ledger.json`](coverage/ledger.json).
- [`bench/alternatives.md`](bench/alternatives.md): how this compares with Loom, GameTest, HeadlessMC and others.
- [Releases](https://github.com/Noisemaker111/modding-accelerator/releases): what changed in each version.
