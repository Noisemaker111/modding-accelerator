# Plan: make Modding Accelerator the fastest honest answer to "did this change work"

## The goal

One command answers "did this change work" in seconds on **Fabric, NeoForge and Forge**, and a
version bump ends **green with a recorded time**. Anything a repo throws at us before code
(JDK, Gradle, plugin, maven) is answered in under a minute by `port -Check`, not discovered
after a five-minute build.

Done means: a stranger installs it on a real repo, edits a file, and gets a truthful PASS/FAIL
with a screenshot or a compile error - on all three loaders.

## Where it stands (2026-10-22)

- Fabric: full loop, 1.5s screen, 2.9s scenario, 1.4s logic (8 tests).
- Stonecutter (Diorama): installs, fast lane 2.1s, world tier skips honestly.
- NeoForge: warm client from ModDevGradle's recipe (no Gradle); screen 3.2s, hot reload 1.5s (agent redefines classes), cold start 18.4s. Schema changes still need a restart.
- Forge (MineColonies, 2102 sources): install 5s, first run 23.9s, one-file edit 2.3s,
  broken file fails in 1.3s. No hot reload. (2026-10-23: also gets a warm client and screenshots -
  see the section at the end.)
- Porting: `-Check` answers in 9-89s; `-Log` groups compiler errors; all six benchmark mods
  repaired green with times recorded (Trinkets 1.15 min, Polymer 8.6 min, Traverse 5.9s, Terrestria 7.5s, Traveler's Backpack 11 min / 196 errors, Artifacts 28.1 min / 100 errors).
- Automatic fixes: `port -Fix` applies `knowledge\fixes.json` and rebuilds until green - Traverse 68 -> 0 errors in 2 rounds / 7.2s, Traveler's Backpack 111 getter sites in 1 round / 4.0s (still red on removed classes).

## The queue, in order

1. ~~Forge/NeoForge warm client~~ **DONE 2026-10-23**: one capture per Forge repo
   (`tools\capture-launch.ps1`), the bridge auto-copied into `mods\`, `.modaccel\forge.args`
   replayed, socket awaited. MineColonies: 854x480 title PNG, cold `ui` 58.6s, warm 0.5s. No hot
   reload on that lane.
2. Finish the four blocked repairs when their dependency is reachable (local mirror via
   Modrinth's maven works for Mod Menu; terraform-api needs a real mirror). Acceptance: 6 of 6
   green with minutes recorded.
3. Structural ports: 1.21.5 removed `RenderSystem.setShader` entirely and `BakedModel` became
   `BlockStateModel`, so Traveler's Backpack is a rendering migration, not a rename. Teach
   `port` to label such errors "structural" and stop pretending a rename table covers them.
4. Publish: one-command installer for all three loaders, README scope table kept true.
   **Done**: all three loaders install, and the README scope table now says Forge gets a warm
   client too.





## Where it stands (2026-10-23)

Done and measured:
- Fast loop + hot reload on Fabric (1.5s), NeoForge (1.5s) and 1.21.11 (1.9s); Forge 1.20.1 has the
  fast lane (2.3s on 2,102 sources) and a warm client from one captured launch, no hot reload.
- Installer for Fabric, Forge and NeoForge, verified from a fresh clone of the public repo. Forge
  gets `.modaccel\bridge-forge.jar` copied in and `modaccel` drops it in `mods\`, replays
  `.modaccel\forge.args` and waits for the socket: MineColonies answers with an 854x480 title PNG,
  cold `ui` 58.6s, warm 0.5s. One capture per Forge repo. A non-26.x repo gets no bridge and the
  installer says why.
- Fault matrix (`bench\fault-matrix.md`): 7 injected faults, tier by tier. Two harness bugs found
  and fixed (world false-pass from a stale argFile; ui hijacked by a bridge jar). Remaining gaps are
  listed there.
- Since the matrix: mixin audit (dead `@Shadow`/`@Inject` fails lint, +0.17s, found TB's dead
  shadow); `world` checks blockstates, item models and lang files in the live server (13-16s);
  `node` asserts the exact value and re-probes drift; `ui` prints only PNGs this run wrote; `mp`
  refuses stale classes and points at `-Cold`; `LabControl` falls back to the current screen when
  `-Dmodlab.screen` is empty; the installer's starter `pure` test makes the first run "1 passed".
- Toolchain gate: JDK by wrapper, loader and pins, `gradlew help` proof, NeoForge target check.
- Porting: `-Api` (version diff, stable-id renames; 1.21.4->1.21.5 in 1.9s: 205 removed, 25 moved,
  3345 members, 921 signatures, 351 replacements of which 222 stable-id), `-Log` (mechanical/
  structural, jar-diff citations, recipes), `-Fix` (11 rule families; Traverse 68 -> 0 in 7.2s, TB
  111 getter sites in 4.0s), `-Check` (pre-build breakage warning, including source references to
  removed symbols).
- All six benchmark mods repaired green with recorded times.
- Bridge jar: screenshots and `ui` on a client that belongs to someone else's mod.
- `restart` = stop, compile, start in one command (49s on the NeoForge node).

Left for the total goal (one command answers "did this change work" on every loader):
1. **Pre-build scan recall.** It sees exact removals in your own source only; renames and member use
   need a reference index to be counted before the first build. Largest gap.
2. **More fix rules.** `knowledge\fixes.json` covers 11 families. Removed or reshaped classes
   (CoreShaders, BakedModel, ThrownPotion), `Inventory.items/selected`, `appendHoverText` and the
   render-state refactor still need a human or a new rule; the fixer names them rather than guessing.
3. **Semantic rewrites stay guided.** Three recipes; a fourth kind of rewrite needs one written.
   Auto-generating render-state classes is deliberately out of scope (green-but-wrong risk).
4. **Fault-matrix gaps that remain:** a warm tier cannot see a fault that predates its process; a
   render exception kills the shared warm client and `ui` reports a raw transport error instead of
   the cause; Forge has no hot reload.
5. **Polish:** one cold-machine run to measure the 280 MB first run; the Forge lane still needs one
   manual capture per repo.


## First run on a cold machine (measured network, estimated total)

Measured here: the JDK download is 134.6 MB and this connection pulled 20 MB in 0.5s
(37.1 MB/s), so the harness's own 280 MB of JDK+JBR is about 8s of transfer plus extraction.
The rest of a first run is Gradle and Minecraft: Loom fetches the game jars and libraries
(typically 200-400 MB) on top. A truly cold machine has not been run end to end; the numbers
above are measured transfer rate x measured sizes, not a timed cold install.
