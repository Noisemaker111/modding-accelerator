# NeoForge 1.21.1 node: what differs from the 26.3 parent

This node compiles the parent's `..\..\src` tree directly (main **and** client), and every
file below is the *only* place a 1.21.1/NeoForge API differs. The parent file at the same
relative path is shadowed here by `layeredSources` in `build.gradle`; the parent tree is not
edited, and the same demo mod runs on both loaders.

## Build system

| 26.3 parent (Fabric) | NeoForge 1.21.1 node | Why |
| --- | --- | --- |
| plugin `net.fabricmc.fabric-loom` | plugin `net.neoforged.moddev` 2.0.148 | ModDevGradle is NeoForge's own plugin; it fetches NeoForge 21.1.264, produces the game jar and runs the client |
| `minecraft "com.mojang:minecraft:…"` + `mappings loom.officialMojangMappings()` | nothing to declare; `neoForge { version = "21.1.264" }` | ModDevGradle applies official Mojang mappings itself, so names stay the ones the parent uses (`Minecraft`, `BlockPos`, …) |
| `splitEnvironmentSourceSets()` + `mods { sourceSet main; sourceSet client }` | one `main` source set holding parent main + parent client + all overrides; `neoForge { mods { prismlab { sourceSet sourceSets.main } }` | NeoForge has no split client source set; client-only classes are marked `dist = Dist.CLIENT` on `@Mod` instead |
| Java 25 compile and runtime | Java toolchain **21**, `options.release = 21` | NeoForge 21.1.x targets and runs on Java 21. The PC only has JDK/JBR 25, so `settings.gradle` adds `org.gradle.toolchains.foojay-resolver-convention` **1.0.0** (0.8.0 crashes on Gradle 9: `JvmVendorSpec does not have member field IBM_SEMERU`) and Gradle downloads a Java 21 |
| Gradle wrapper 9.7.1 | same wrapper 9.7.1 | ModDevGradle 2.0.148 runs on it |
| `fabric.mod.json` (entrypoints + `"minecraft": "~26.3"`) | `META-INF/neoforge.mods.toml` (`modLoader = "javafml"`, `[[mixins]] config = "prismlab.mixins.json"`, version from `processResources`) | NeoForge's mod metadata. The parent's `fabric.mod.json` is still copied into the jar and ignored |
| resources copied from `../../src` as they are | same paths, but `processResources { duplicatesStrategy = EXCLUDE }` | resources are a plain copy (no layering), so without this a node file that shadows a parent file fails the build with "Entry … is a duplicate" |
| `runClientWarm` on 27399 / 27398, JBR agent | `clientWarm` on **27599** (`gameDirectory run/`), no `-javaagent` | own ports so all three nodes can run at once; hot reload is not wired here (see below) |
| `fabricApi.configureTests` gametest source set | not copied | the parent gametest sources use Fabric's gametest API; this node's deliverable is the warm client |
| recipe at `data/prismlab/recipe/prism_shard.json`, `result: {"id": …}` | same path (1.21.1 reads `recipe/`, `elementsDirPath`), but the JSON is shadowed: object ingredients `{"item": …}`, `result: {"id": …}` | 1.21.1 rejects 26.3's string ingredients (`Map entry 'A' … Not a JSON object`) and `{"item": …}` as a result (`No key id in MapLike`) |
| loot table at `data/prismlab/loot_table/blocks/prism_node.json` | unchanged, inherited | 1.21.1 also reads `loot_table/`; the parent file parses as is |

## Mod entry points

| Parent | This node |
| --- | --- |
| `PrismLab implements ModInitializer` + `onInitialize()` | `@Mod(PrismLab.MOD_ID)` with a `(IEventBus modBus, ModContainer container)` constructor (`src/main/java/com/prismlab/PrismLab.java`) |
| `PrismLabClient implements ClientModInitializer` | `@Mod(value = "prismlab", dist = Dist.CLIENT)` + `@EventBusSubscriber(modid = "prismlab", value = Dist.CLIENT)` (`src/client/java/com/prismlab/client/PrismLabClient.java`) |
| `ClientTickEvents.END_CLIENT_TICK.register(client -> tick())` | `@SubscribeEvent ClientTickEvent.Post` → `LabControl.clientTick()` |
| `dev/modlab/client/LabEntry.java` (Fabric entrypoint that starts the socket) | same file shadowed to a no-op stub; `PrismLabClient`'s constructor calls `LabControl.startIfRequested()` |

## Main sources

`PrismLab.java`
- `net.fabricmc.api.ModInitializer` → `net.neoforged.fml.common.Mod` (+ `IEventBus`, `ModContainer`).
- `net.minecraft.resources.Identifier` → `net.minecraft.resources.ResourceLocation`;
  `Identifier.fromNamespaceAndPath` → `ResourceLocation.fromNamespaceAndPath`.

`PrismItems.java`
- **Registration**: `Registry.register(BuiltInRegistries.ITEM, key, item)` in a static field →
  `DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID)` with
  `ITEMS.registerSimpleItem("prism_shard", props)` and `ITEMS.register(modBus)`.
  NeoForge 1.21.1 freezes the vanilla registries before mods construct, so the parent's direct
  registration fails at startup with `IllegalStateException: Registry is already frozen`.
- 26.3: `CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(tab -> tab.accept(PRISM_SHARD));`
- 1.21.1: `modBus.addListener(...)` handling `BuildCreativeModeTabContentsEvent`, then
  `if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(PRISM_SHARD.get());`
- `Item.Properties.setId(itemKey)` → gone (1.21.1 has no id on item properties).

`PrismBlocks.java`
- `DeferredRegister.Blocks` + `registerBlock("prism_node", PrismNode::new, props)` and
  `PrismItems.ITEMS.registerSimpleBlockItem("prism_node", PRISM_NODE)` instead of
  `Registry.register` + `PrismItems.register`.
- same creative-tab delta for `CreativeModeTabs.BUILDING_BLOCKS`;
- `BlockBehaviour.Properties.setId(...)` → removed; `Item.Properties.useBlockDescriptionPrefix()` → removed (both 26.3-only).

`PrismBlockEntities.java`
- `FabricBlockEntityTypeBuilder.create(...).build()` →
  `BlockEntityType.Builder.of(PrismNodeEntity::new, block).build(null)` inside a
  `DeferredRegister<BlockEntityType<?>>`; the block is read with `PrismBlocks.PRISM_NODE.get()`
  (deferred holders are only bound when the block registry event has run, which is before the
  block-entity-type event).
- `PrismNodeEntity`/`PrismNode` reach the type with `PrismBlockEntities.PRISM_NODE.get()`.

`PrismLabCommands.java`
- `CommandRegistrationCallback.EVENT.register((dispatcher, ctx, sel) -> …)` →
  `NeoForge.EVENT_BUS.addListener(...)` on `RegisterCommandsEvent`, `event.getDispatcher()`.
- `BuiltInRegistries.BLOCK.getValue(ResourceLocation)` → `.get(ResourceLocation)` (1.21.1's
  `Registry` has `get(ResourceLocation)`, 26.3 renamed it `getValue`).
- `player.level()` → `player.serverLevel()`.

`block/PrismNode.java`
- `BaseEntityBlock.codec()` is **abstract** in 1.21.1 (26.3 dropped it) →
  `public static final MapCodec<PrismNode> CODEC = simpleCodec(PrismNode::new);` plus the override.
- `BaseEntityBlock.getRenderShape` returns **`RenderShape.INVISIBLE`** in 1.21.1, so the block is
  in the world, ticking and assertable, but draws nothing → override it to `RenderShape.MODEL`.
  This is the one delta only a screenshot could catch.
- `player.sendOverlayMessage(Component…)` (26.3) → `player.displayClientMessage(Component…, true)`.

`block/entity/PrismNodeEntity.java`
- 26.3 `saveAdditional(ValueOutput)` / `loadAdditional(ValueInput)` with `putInt`/`getIntOr`
  → `CompoundTag` + `HolderLookup.Provider`: `tag.putInt("charge", …)`, `tag.getInt("charge")`.
- `getUpdateTag(HolderLookup.Provider)` / `saveCustomOnly(...)` / `getUpdatePacket()` keep the same
  names, so the dedicated-server sync fix carries over unchanged.

`mixin/ServerLoadMixin.java`
- `MinecraftServer.tickServer()` (no arguments in 26.3) → `tickServer(BooleanSupplier)` in 1.21.1,
  so the handler takes the supplier: `prismlab$onTick(BooleanSupplier hasTimeLeft, CallbackInfo info)`.
- The mixin applies: `PRISMLAB-MIXIN active tick=600` appears in the client log (the parent
  `prismlab.mixins.json` is reused as is).

## Client sources

`client/PrismScreen.java`
- `extractBackground(GuiGraphicsExtractor, …)` → `renderBackground(GuiGraphics, …)`
- `extractRenderState(GuiGraphicsExtractor, …)` → `render(GuiGraphics, …)`, and **do not** end
  with `super.render(...)`: 1.21.1's `Screen.render` calls `renderBackground` and then the
  widgets, so a super call repaints the backdrop over the panel. The node calls
  `renderBackground`, draws the panel, then iterates `this.renderables`.
- `graphics.text(font, …)` → `graphics.drawString(font, …, shadow)`; `graphics.centeredText` →
  `graphics.drawCenteredString`; `graphics.outline(...)` → four 1px `graphics.fill(...)` calls
- `graphics.blit(…)` → the shard icon is a plain cyan `fill` swatch (1.21.1's blit overloads all
  want integer sprite coordinates; the screen is not pixel-compared against a golden)
- `this.minecraft.gui.setScreen(null)` → `this.minecraft.setScreen(null)`

`dev/modlab/client/LabControl.java` (same socket protocol: `root`, `ui`, `node`,
`scenario <steps>`, `quit`; port from `-Dprismlab.lab.port`)
- `client.gui.screen()` → `client.screen`; `client.gui.setScreen(x)` → `client.setScreen(x)`
- `client.gameRenderer.mainRenderTarget()` → `client.getMainRenderTarget()`
- `client.gui.toastManager().clear()` → `client.getToasts().clear()` (`ToastComponent`)
- `client.resizeGui()` → `client.resizeDisplay()`
- `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` does not exist in 1.21.1; only
  `Screenshot.takeScreenshot(RenderTarget)` returning a `NativeImage`. `settle` and `capture` are
  therefore synchronous here (no async readback phase, no black-frame retry).
- `NativeImage.getPixelsABGR()` → `getPixelsRGBA()` (1.21.1 predates the ABGR swap)
- `MouseButtonEvent`/`MouseButtonInfo(0, 0)` → `button.mouseClicked(x, y, 0)` (1.21.1's three-arg form)
- `new GameRules(FeatureFlags.VANILLA_SET)` → `new GameRules()` (1.21.1 has no flag-set constructor)
- `root()` honours `-Dmodlab.root`, and `warm.ps1` connects to `localhost`: the socket is bound to
  `InetAddress.getLoopbackAddress()`, which is IPv6 `::1` here, so `127.0.0.1` is refused.
- `place=` levels the camera first and removes the previously placed node, then aims the real
  client camera at the new block, so repeated runs do not stack nodes in front of the lens and
  the screenshot always shows the block.
- world bootstrap: `run/saves/lab` does not exist on a fresh run directory, so the first scenario
  creates it through `createWorldOpenFlows().createFreshLevel("lab", settings, options,
  WorldPresets::createNormalWorldDimensions, client.screen)` (same signature as 1.21.11) and the
  vanilla path opens it; later runs use `openWorld("lab", …)`.

`dev/modlab/client/HotUi.java`
- stubbed out. The parent's `HotUi` recompiles `../../src` in place, and those sources import
  Fabric APIs that do not exist on this classpath, so hot reload does not apply here: a code
  change means restarting the client. `reload` returns the normal class loader and
  `redefineMain` reports `hot reload not wired in the neoforge node`. No `-javaagent` is passed
  to the run.

## Unchanged and compiling straight from the parent

`PrismMath` (pure logic, no Minecraft classes), `assets/` (blockstates, models, textures, lang;
the 1.21.4+ `assets/prismlab/items/*.json` files are ignored by 1.21.1, which uses
`models/item` + `blockstates`), `PrismNodeEntity`'s sync overrides, and the whole harness step
list (`place=`, `wait=`, `assert=`, `settle`, `capture=`, `screen=`, `click=`).
