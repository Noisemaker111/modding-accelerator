# 1.21.11 node: what differs from the 26.3 parent

This node compiles the parent's `..\..\src` tree directly. Every file below is the *only*
place a 1.21.11 API differs; the parent file at the same relative path is shadowed here
(`build.gradle` → `layeredSources`), and the parent tree is not edited.

## Build

| 26.3 parent | 1.21.11 node | Why |
| --- | --- | --- |
| Loom `1.18-SNAPSHOT` | Loom `1.19.2` | the version with 1.21.11 support |
| Gradle wrapper `9.7.1` | Gradle wrapper `9.8.0` | Loom 1.19.2 refuses to load below 9.8.0 |
| plugin `net.fabricmc.fabric-loom` | plugin `net.fabricmc.fabric-loom-remap` | the plain plugin targets non-obfuscated Minecraft; with it, `mappings loom.officialMojangMappings()` fails with `Cannot use Mojang mappings in a non-obfuscated environment`. `-remap` is the obfuscated target and keeps officialMojangMappings working. |
| `implementation` loader + fabric-api | `modImplementation` loader + fabric-api | 1.21.11 is obfuscated, so Fabric API must be remapped out of intermediary. With `implementation` the compiler reports `cannot access class_5321` / `class_2586`. |
| `fabric_api_version=0.163.0+26.3` | `0.141.6+1.21.11` | matching build for this Minecraft |
| `"minecraft": "~26.3"` in the shared `fabric.mod.json` | rewritten to `"~1.21.11"` while copying resources | the one shared resource that names a Minecraft version; the parent file is untouched |
| `runClientWarm` on 27399 / 27398 | `clientWarm` on 27499 / 27498 | both nodes can run at once |
| `fabricApi.configureTests` gametest source set | not copied | the parent gametest sources use 26.3 renderer types; this node's deliverable is the warm client |

## Main sources (4 overrides, plus one node-only class)

`src/main/java/com/prismlab/PrismItems.java`
- 26.3: `CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(tab -> tab.accept(PRISM_SHARD));`
- 1.21.11: `ItemGroupEvents.modifyEntriesEvent(tab("ingredients")).register(entries -> entries.accept(PRISM_SHARD));`
- `CreativeModeTabs.INGREDIENTS`/`BUILDING_BLOCKS` are `private` in 1.21.11, so the key is
  rebuilt: `ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("ingredients"))`.

`src/main/java/com/prismlab/PrismBlocks.java`
- same item-group delta for `"building_blocks"`; `BlockBehaviour.Properties.setId(...)` and
  `Item.Properties.useBlockDescriptionPrefix()` both exist in 1.21.11, so they are unchanged.

`src/main/java/com/prismlab/block/PrismNode.java`
- 26.3: no `codec()`; 1.21.11: `BaseEntityBlock.codec()` is abstract →
  `public static final MapCodec<PrismNode> CODEC = simpleCodec(PrismNode::new);` plus
  `protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }`.
- 26.3: `player.sendOverlayMessage(Component...)`; 1.21.11: `player.displayClientMessage(Component..., true)`.

Unchanged and compiling straight from the parent: `PrismLab` (`net.minecraft.resources.Identifier`
exists in both; `Identifier.fromNamespaceAndPath`), `PrismItems`' `Item.Properties.setId`,
`PrismNodeEntity` (`ValueInput`/`ValueOutput` and `getMaxLocalRawBrightness` exist in both),
`PrismBlockEntities`, `PrismMath`, `ServerLoadMixin` (the mixin applies: the client logs
`PRISMLAB-MIXIN active tick=600`).

## Client sources (3 overrides)

`src/client/java/com/prismlab/client/PrismScreen.java`
- `extractBackground(GuiGraphicsExtractor, ...)` → `renderBackground(GuiGraphics, ...)`
- `extractRenderState(GuiGraphicsExtractor, ...)` → `render(GuiGraphics, ...)`
- `graphics.text(font, text, x, y, colour, shadow)` → `graphics.drawString(...)`
- `graphics.outline(x, y, w, h, colour)` → four 1px `graphics.fill(...)` calls
- `graphics.blit(Identifier, x1, y1, x2, y2, u0, u1, v0, v1)` → not used; the shard icon is a
  cyan `fill` swatch (the 1.21.11 blit signature takes a `RenderPipeline` and integer sprite
  coordinates; the screen is not pixel-golden compared in this node)
- `this.minecraft.gui.setScreen(null)` → `this.minecraft.setScreen(null)`

`src/client/java/dev/modlab/client/LabControl.java` (same socket protocol: `root`, `ui`,
`node`, `scenario <steps>`, `quit`; client port from `-Dprismlab.lab.port`)
- `client.gui.screen()` → `client.screen`; `client.gui.setScreen(x)` → `client.setScreen(x)`
- `client.gameRenderer.mainRenderTarget()` → `client.getMainRenderTarget()`
- `client.gui.toastManager().clear()` → `client.getToastManager().clear()`
- `client.resizeGui()` → `client.resizeDisplay()`
- `target.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false)` →
  `new MouseButtonInfo(0, 0)`: the primary button is 0 here, 1 in 26.3
- `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` is the same call, but the
  image type is `com.mojang.blaze3d.platform.NativeImage` (imported explicitly)
- world bootstrap: this node has no `run/saves/lab`, so the first scenario creates it with
  vanilla's own path, `createWorldOpenFlows().createFreshLevel("lab", settings, options,
  WorldPresets::createNormalWorldDimensions, screen)`, then opens it with `openWorld("lab", ...)`
- settle: frames are compared with a tolerance instead of exact equality. Measured on this
  machine, two visibly identical 1.21.11 frames differ by up to 14 levels per channel (323k
  pixels change, none visually), so `settle` passes at max channel delta ≤ 16 and samples are
  6 ticks apart (1.21.11's async readback returns an all-black image if asked back-to-back).
- hot reload works: `HotUi` is overridden here (see below), `LabControl` calls it, and
  `warm.ps1 ui` shows an edit to this node's sources without a restart. Numbers in
  `MEASURED.md` → "Hot reload".

## Hot reload (the 1.21.11 lane)

The parent's `HotUi` assumes one source tree at the project root. This node's sources are
`..\..\src` plus overrides here, so `dev\modlab\client\HotUi.java` is overridden too:

- **Layer the sources like the Gradle build does.** `layered(override, parent)` collects
  overrides first and adds every parent file whose relative path is not overridden, so a class
  is never defined twice. `..\..\src\main\java` + `src\main\java` for main,
  `..\..\src\client\java` + `src\client\java` for client.
- **Compile only the side that changed** (`mainStamp` / `clientStamp` from source mtimes).
  A screen edit no longer pays for the main classes; javac over the remapped Minecraft
  classpath is 0.6–1.4 s per side here, which was the whole difference between 2.2 s and 1.7 s.
- **Skip `dev\modlab\*` when compiling client sources.** Those classes are never served from
  the fresh loader (`ClientFirst` delegates them to the game's loader), so compiling
  `LabControl` and `HotUi` was pure cost.
- **Same classpath rule as the parent**: `java.class.path` minus Gradle's `build\classes`
  and `build\resources`, so freshly compiled code is not shadowed by the build output.
- **Fallback javac.** The game runs on JBR; if that JVM ever ships without `jdk.compiler`,
  `ToolProvider` returns null and the node now compiles with `.tools\jdk-*\bin\javac.exe`
  in a child process instead of failing.
- Ports: agent 27498, client 27499 (the parent's `HotUi` default is 27398).

`src\client\java\dev\modlab\client\LabControl.java` (this node's override) now:
- calls `HotUi.redefineMain(...)` before the screen opens (`ui`, and once per `node` run),
  and reports the agent's answer in the result lines as `main: ...`;
- loads `-Dmodlab.screen` and every `screen=` step through `HotUi.reload(...)`, so client
  classes come from the source on disk;
- turns `client.options.onboardAccessibility` off and reopens the screen under test if
  1.21.11's one-time accessibility onboarding replaced it (otherwise the screenshot is the
  wrong screen);
- captures the `ui` screenshot twice (4 ticks apart; second overwrites the first) because
  1.21.11's async readback hands back the frame from before a change.

New main class, `src\main\java\com\prismlab\PrismBanner.java`: one string that the screen
draws, so a main-source edit has something a screenshot can show ("Prism node online",
drawn under the hint). It is the probe for main-class redefinition on this node.

`warm.ps1` gained three things the hot lane needed:
- `start` waits for a client that is already booting, replaces one that never answers within
  150 s, and retries once when the Gradle launcher exits without a client (a Loom
  source-remap failure, seen 3 times in this session);
- `stop` waits for the game process to actually exit instead of returning while it saves;
- `ui` is unchanged in shape: it routes to the client's `ui` command on port 27499.
