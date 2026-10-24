# Modding Accelerator

**Update a Fabric mod from Minecraft 1.21.5 to 1.21.6 with one command, then see it load in the real game.**

```sh
modaccel port --root /path/to/mod --from 1.21.5 --to 1.21.6 --fix
modaccel ui-check --root /path/to/mod     # real 1.21.6 client: asks Fabric whether the mod loaded
```

`port --fix` moves the Minecraft, Yarn, Fabric Loader and Fabric API versions, finds 1.21.6
releases of the mod's dependencies, rebuilds, checks every mixin against the 1.21.6 game, reads the
packaged `fabric.mod.json` back to make sure Fabric will accept 1.21.6, and records a byte-exact
undo (`port --undo <path>`). Nothing is edited by hand.

## Measured on five mods it was never tuned on

Popular open-source Fabric mods, untouched, at their last 1.21.5 commit. Windows 11, Ryzen 9 3900X,
warm caches: each mod was built once at 1.21.5 first, and the 1.21.6 game files were already
downloaded.

| Mod (Modrinth downloads) | What the port changed | `port` | `ui-check` |
|---|---|---|---|
| Krypton (45M) | Game, Yarn, Loader versions; Loom 1.6 and Gradle 8.6 kept | 14.0 s | loads, 23.0 s |
| Falling Leaves (28M) | Versions, Fabric API, Cloth Config, Mod Menu, Serene Seasons; Fabric Seasons kept (compile-only, no 1.21.6 release) | 25.8 s | loads, 29.4 s |
| Animatica (22M) | Versions, Fabric API | 17.1 s | loads, 43.1 s |
| Accurate Block Placement Reborn (4.3M) | Versions, Fabric API, Cloth Config, Mod Menu | 13.3 s | loads, 29.9 s |
| IJM's Tweaks (3.0M) | Versions, Fabric API, Mod Menu, Fabrishot, YACL 3.7.1 (3.8.x needs a newer Loom) | 35.7 s | loads, 29.0 s |

"Loads" means the client reached the title screen, Fabric reported the mod loaded, and the
1280×720 frame was inspected. All five changed only `gradle.properties`; for Accurate Block
Placement that matches the author's own 1.21.6 release, which also changed only that file.
IJM's Tweaks is at its `v1.4.0+1.21.5` tag, because its last 1.21.5 commit does not compile.

Before this change the same five gave 2 ports out of 5, and neither of those had been loaded in a
client. Every failure was a general tool bug, now fixed: the mixin audit rejected correct handlers
on static methods; a compile-only integration blocked the port; the newest YACL needed a newer Loom
than the project's; Modrinth's Maven served the Forge jar of a multiloader dependency; and
`ui-check`'s own hook crashed the client of a mod that runs without Fabric API (Krypton).
The two mods the tool was developed on still port and load: F3Nope in 22.6 s (its `fabric.mod.json`
now changes by one line instead of being rewritten) and AppleSkin in 40.8 s.

## Other hops and tools: less proven

These run, but each is shown on one mod or measured below what earlier notes claimed:

- **NeoForge 1.21.1 → 1.21.4:** one mod (ZombieApocalypseAddon), no hand edits; 165 s on its
  current upstream commit.
- **Bigger jumps** (1.21.1 → 1.21.6, 1.20.1 → 1.21.6) stop with the errors that remain, named.
- **26.3:** one mod (AppleSkin NeoForge) reaches a packaged build; one file was written by hand.
- **`fast`:** compiles and runs the mod's own `@Tag("pure")` tests without the game. On this repo's
  lab mod: 48 s cold, 4.2 s with nothing changed, 8.3 s after a one-file edit. A mod with no pure
  tests gets nothing from it.
- **Screen checks:** `ui-check` restarts the client (23–44 s in the trial above). Swapping an
  edit into a running client exists only in the older Windows lab lane, not in the portable command.

**Not built:** any version to any version, and any loader to any loader. That is the goal in
[`ROADMAP.md`](ROADMAP.md), not current capability.

Beta 0.1.0, published 24 October 2026, does not include the fixes above yet · [download](https://github.com/Noisemaker111/modding-accelerator/releases/tag/v0.1.0-beta.1) · [porting coverage](https://noisemaker111.github.io/modding-accelerator/progress.html)

## Quick start

Download the portable ZIP, check it against the `.sha256`, extract it, then:

```sh
./modaccel support
./modaccel doctor --root "/path/to/mod"
./modaccel port --root "/path/to/mod" --from 1.21.5 --to 1.21.6 --fix
./modaccel ui-check --root "/path/to/mod"                # real client, mods-loaded check, capture
./modaccel game-stop --root "/path/to/mod"
./modaccel fast --root "/path/to/mod"                    # compile + pure tests, no game
./modaccel world-check --root "/path/to/mod" --world lab # loaded save, optional assertions
```

Windows: `modaccel.cmd`. Any shell: `java -jar modaccel.jar` with the same arguments. Packages
need Java 17+; a Windows machine with none downloads a verified runtime once. To build from
source: `java tools/cli/Bootstrap.java . package --json` with a JDK 17+.

## Limits

- **One proven hop.** Fabric 1.21.5 → 1.21.6 is the only hop tried on mods the tool was not built
  around. Loader changes and multi-hop chains are not built.
- **Source checkouts only.** A compiled mod JAR cannot be ported.
- **Big jumps stop named.** The 1.21.6 storage and GUI families still need a human, and the receipt lists them.
- **Loading is not behaviour.** `ui-check` proves the mod loads and renders a frame; what the mod
  does in a world needs `world-check` with an assertion.
- **Game checks are verified on Windows.** Linux and macOS run the portable checks in CI, not the game.
- **No AI porting.** It is mapping diffs, typed rules and measured repairs.

## Details and evidence

- [**Porting coverage**](https://noisemaker111.github.io/modding-accelerator/progress.html) — every hop that was run. Generated from [`coverage/ledger.json`](coverage/ledger.json).
- [`docs/details.md`](docs/details.md) — portable command internals, older Windows lanes, scenarios, multiplayer, the porting toolbox, measurements.
- [`bench/alternatives.md`](bench/alternatives.md) — sourced comparison with Loom, GameTest, HeadlessMC, MC-Runtime-Test, mc-mod-porter and others, including where they win.
- [`PLAN.md`](PLAN.md) — dated evidence and the current resume point.
- [`ROADMAP.md`](ROADMAP.md) — the any-version, any-loader goal and what's left, in order.
