# NeoForge 1.21.1 node: measured

All numbers from commands run in this directory on 2026-10-21/22, same PC. Gradle runs on
JDK 25 (`..\..\.tools\jdk-25.0.4.1+1`); the game itself runs on the **Java 21** toolchain that
ModDevGradle resolves through `foojay-resolver-convention` (NeoForge 21.1.x needs 21).

## Build

| What | Seconds | Command |
| --- | --- | --- |
| first artifact creation in this directory | **~290** | `.\gradlew.bat compileJava` (downloads NeoForge 21.1.264, NeoForm, decompiles + recompiles Minecraft, downloads a Java 21) |
| clean build, dependency caches warm | **4.9**, 6.3 | `.\gradlew.bat clean build` |
| build with nothing to do | 0.9 | `.\gradlew.bat build` |
| first client launch (also downloaded 3,888 vanilla assets) | ~180 before the window came up | `.\warm.ps1 start` |

## Warm client (ports 27599 / 27598)

| What | Seconds |
| --- | --- |
| client start to a listening socket (title screen) | **19.2**, 19.3, 21.6, 21.6 (4 runs; includes Gradle configure + compile + game boot) |
| `node` scenario, first one after a start, world already saved | **6.11**, 6.27, 6.36 |
| `node` scenario, after deleting `run\saves\lab` (creates the world) | **13.03** |
| `node` scenario, warm (already in the world) | **2.62**, 2.98, 2.61, 2.60 |
| `ui` command (open PrismScreen, screenshot, click "More shards", screenshot) | **1.17**, 1.18, 1.19 |

The deliverable run, verbatim (`.\warm.ps1 node`):

```
PASS  13.03s  created world lab | placed prismlab:prism_node at -376, 68, -241 | assert charge() = 1 | settled (max channel delta 9) | screenshot neoforge-node.png 854x480
PASS   2.62s  placed prismlab:prism_node at -376, 68, -241 | assert charge() = 2 | settled (max channel delta 7) | screenshot neoforge-node.png 854x480
PASS   1.17s  screenshot prismlab-ui-neoforge.png 854x480 | clicked 'More shards' | screenshot prismlab-ui-neoforge-clicked.png 854x480
```

`charge()` is 1-2 after 40 ticks because the scenario clears the spot before placing, so the
block entity really is two seconds old. The camera is aimed at the block that was just placed,
so the PNG shows the node itself (navy block, cyan diamond, light level 7) with the crosshair
and its outline.

## Screenshots

- `build\run\warm\shots\neoforge-node.png` — **854 x 480**, 369,620 bytes (forest, one node
  in frame, hotbar and crosshair visible)
- `build\run\warm\shots\prismlab-ui-neoforge.png` — 854 x 480, 21,924 bytes ("Prism Shard x5")
- `build\run\warm\shots\prismlab-ui-neoforge-clicked.png` — 854 x 480, 21,921 bytes ("x6"
  after the real click; the two PNGs differ, so the click is observed in pixels)
- Copies survive in `..\..\.workers\neo-final\` because `clean` deletes `build\`.

## Log proof

- Mixin applies: `PRISMLAB-MIXIN active tick=0` on every run (the parent's
  `prismlab.mixins.json` + `ServerLoadMixin`, retargeted to `tickServer(BooleanSupplier)`).
- No `ERROR` and no `Parsing error` lines in the final client log; `Prism Lab demo mod
  initialized (NeoForge 1.21.1)` and `PRISMLAB-READY 27599` are both there.

## What failed first, and why

1. **`foojay-resolver-convention` 0.8.0** (the version ModDevGradle's README shows) crashes on
   Gradle 9.7.1: `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field
   IBM_SEMERU`. Fixed by 1.0.0.
2. **Direct `Registry.register(BuiltInRegistries.ITEM, …)`** at mod construction dies with
   `java.lang.IllegalStateException: Registry is already frozen`
   (`MappedRegistry.validateWrite` ← `Item.<init>` ← `PrismItems`. NeoForge 1.21.1 closes the
   vanilla registries before mods construct, so items/blocks/block entities moved to
   `DeferredRegister` on the mod bus. This is the biggest delta from the parent, which registers
   straight into `BuiltInRegistries`.
3. **Compile-time API breaks** (all fixed, see DELTAS.md): `Screenshot.takeScreenshot(RenderTarget,
   Consumer)` and `NativeImage.getPixelsABGR()` do not exist in 1.21.1, `new
   GameRules(FeatureFlags.VANILLA_SET)` does not either, and `Registry.getValue(ResourceLocation)`
   is `get(ResourceLocation)`.
4. **The block rendered invisible while every assertion passed.** `BaseEntityBlock.getRenderShape`
   returns `RenderShape.INVISIBLE` in 1.21.1; the fix is one override. Only the screenshot caught
   it — the scenario said `assert charge() = 1` on a block you could not see.
5. **The screen drew only the title and buttons.** 1.21.1's `Screen.render` calls
   `renderBackground` itself, so the panel drawn before `super.render()` was painted over. The
   panel is now drawn after `renderBackground` and the widgets are rendered last.
6. **Recipe JSON**: `data\prismlab\recipe\prism_shard.json` failed with `Map entry 'A' … Not a
   JSON object` and then `No key id in MapLike[{"item":…}]`. 1.21.1 wants object ingredients
   (`{"item": …}`) and a `result` with `id`. Shadowing the parent's file then failed the build
   with `Entry … is a duplicate but no duplicate handling strategy has been set`;
   `processResources { duplicatesStrategy = EXCLUDE }` makes the node's resource win.
7. **The driver could not see the client although it was up.** `LabControl` binds
   `InetAddress.getLoopbackAddress()`, which on this JDK is **`::1`**, so `127.0.0.1` was refused;
   and `root()` resolved to `<project>\run`, not the project, so the root handshake never matched.
   The node's `warm.ps1` connects to `localhost` and `root()` honours `-Dmodlab.root`.
8. **One 10-minute stall** launching a client right after quitting the previous one: Gradle
   blocked on a daemon/cache lock (`Daemon vm is shutting down` + a `PersistentDaemonRegistry`
   file-lock error in `~\.gradle\daemon\9.7.1`). `gradlew --status` showed an IDLE daemon after the
   old run finished, and the next start came up normally. Not reproduced after that.

## Slower / faster than the other nodes

Parent 26.3 warm client: `node` 2.1 s, `ui` 1.5 s, scenario with settle 2.8 s (its own run
history). The 1.21.11 node: `node` 4.1 s warm / 9-10 s when it still has to open the world,
`ui` 1.2 s.

- **`node` 2.6 s warm is the same class as the parent's 2.1 s** and better than the 1.21.11
  node's 4.1 s, mostly because `settle` here is synchronous (`Screenshot.takeScreenshot`
  returns the image in 1.21.1) instead of two async readbacks 6 ticks apart.
- **`ui` 1.17 s** is slightly faster than both.
- **Client start 19-22 s** matches the 1.21.11 node's 19.8-25 s; NeoForge loads more jars than
  Fabric but the JVM start dominates.
- **The build is cheap (4.9 s clean)** because the configuration cache is on and the NeoForm
  artifacts are cached, but the *first* build is ~5 minutes (decompile) versus the Fabric node's
  plain unobfuscated build.

## Caveat

This node compiles the parent's `..\..\src` live, so a parent change that uses a 26.3-only API
breaks this build until a matching override is added here (same caveat as the 1.21.11 node).

## Hot screen reload — 2026-10-22 (same behaviour as the Fabric parent)

A screen edit now reaches the screenshot in about **1.5s with no client restart**, the same shape
as the parent: `HotUi` recompiles the node's sources plus the parent files the node inherits
(`PrismScreen` lives here as an override), then asks `LabAgent` over loopback to
`redefineClasses` the bodies. NeoForge has one loader for the mod, so no child-first loader is
needed as on Fabric.

| What | Seconds | Command |
| --- | --- | --- |
| edit `HINT` in `PrismScreen` → new text on screen | **1.54**, 1.49, 1.44, 1.84 | `.\warm.ps1 ui`, `..\..\modaccel.ps1 -Version neoforge-1.21.1 ui` |
| first `ui` after a client start (all 11 classes compiled, 8 redefined) | 2.39, 2.40 | `.\warm.ps1 ui` |
| revert of the same edit, hot again | 1.44, 1.56 | `.\warm.ps1 ui` |
| client start from the saved recipe, agent attached | 18.7, 18.4 (was ~19.2 through Gradle) | `.\warm.ps1 start` |

Evidence: `build\run\warm\shots\before-hot-reload.png` reads "Click More shards.",
`after-hot-reload.png` reads "Hot reloaded. No restart." — both from the same JVM (pid 10176,
started 19:49:40, `javaagent` in its command line). `ui` prints `main: redefined 1 class` for the
edit and `redefined 8 classes` for the first full compile, so the count is the agent's, not an
assumption. The test string was reverted (blob identical to HEAD); `.\warm.ps1 node` still passes
(`charge() = 1`).

What changed:

- `warm.ps1` launches from ModDevGradle's saved recipe (`clientWarmRunVmArgs.txt` +
  `.modaccel\neoforge-cp.args` + `clientWarmRunProgramArgs.txt`) instead of `gradlew runClientWarm`,
  and adds `-javaagent:.modaccel\agent.jar -Dprismlab.agent.port=27598`. The agent jar is built
  with `--release 21`: the game runs on the Java 21 toolchain, and a class file 69 premain is
  refused there.
- `HotUi` compiles into `.modaccel\hot\classes` with the game's own classpath (minus the mod's
  build output) and skips `dev.modlab.*` so the harness never redefines itself mid-request.
- `LabControl` reports `main: <result>` before the screen is constructed, as the parent does.

Still needs a restart: adding/removing a field or method (reported as
`needs restart: PrismScreen (UnsupportedOperationException: class redefinition failed: attempted
to change the schema ...)` — the client keeps running and keeps the old screen), registry or mixin
changes, and edits to the harness itself (`dev.modlab.*`). Those need `.modaccel\client.pid` gone
first: while the client runs it holds `build\moddev\artifacts\neoforge-21.1.264.jar`, so
`gradlew compileJava` fails with `AccessDeniedException` (stop → compileJava 2s → start 18.7s).

