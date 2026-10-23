# Minecraft version changes

Migration knowledge base for the porting tool. One row is one thing that breaks a mod on that version jump.

- `Kind` is one of `rename`, `moved`, `signature`, `removed`, `behaviour`, `data-format`.
- `From` and `To` are the exact old and new symbol or JSON shape; `none` means nothing replaces it.
- `Source` is the page the row was verified against this session. Codes: `P<version>` = NeoForge Mod Migration Primer for that version (it documents the jump that *ends* at that version), `FA26.1` = Fabric API 26.1 rename list. Full URLs are listed after the tables.
- Jump `1.21.1 -> 1.21.11` spans 1.21.2 through 1.21.11, so each row names the page for the version that introduced it.
- The primers call themselves "high level, non-exhaustive", so this list is the substantiated subset, not everything that changed.

## 1.21.1 -> 1.21.11

| # | Kind | From | To | Note | Source |
| --- | --- | --- | --- | --- | --- |
| 1 | signature | `new Block(BlockBehaviour.Properties.of())` | `new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ns, name))))` | Pass the block ResourceKey through setId before constructing the Block; construction throws if the id is missing. | P1.21.2 |
| 2 | signature | `new Item(new Item.Properties())` | `new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ns, name))))` | Item.Properties#setId is mandatory because the default description id and model are now derived from that key. | P1.21.2 |
| 3 | behaviour | `item name and model implied by the registry id` | `DataComponents.ITEM_NAME and DataComponents.ITEM_MODEL, overridable via Item.Properties#overrideDescription and #overrideModel` | Set the component or the override when an item must not use its own id for name or model. | P1.21.2 |
| 4 | data-format | `assets/<ns>/models/item/<name>.json as the only item render definition` | `assets/<ns>/items/<name>.json (Client Items, with a model field) linking to assets/<ns>/models/item/<name>.json` | Add the items/<name>.json definition or the item falls back to a missing model. | P1.21.4 |
| 5 | signature | `Entity#readAdditionalSaveData(CompoundTag) and Entity#addAdditionalSaveData(CompoundTag)` | `Entity#readAdditionalSaveData(ValueInput) and Entity#addAdditionalSaveData(ValueOutput)` | Replace direct NBT reads and writes with ValueInput get* / read and ValueOutput put* / store. | P1.21.6 |
| 6 | signature | `BlockEntity#loadAdditional(CompoundTag, HolderLookup.Provider) and BlockEntity#saveAdditional(CompoundTag, HolderLookup.Provider)` | `BlockEntity#loadAdditional(ValueInput) and BlockEntity#saveAdditional(ValueOutput)` | The registry lookup now comes from the ValueInput/ValueOutput context instead of a separate provider argument. | P1.21.6 |
| 7 | behaviour | `BlockBehaviour#onRemove` | `BlockEntity#preRemoveSideEffects and BlockBehaviour#affectNeighborsAfterRemoval` | Split removal logic: drop stored contents in preRemoveSideEffects, send neighbour updates in affectNeighborsAfterRemoval. | P1.21.5 |
| 8 | removed | `net.minecraft.world.Containers#dropContentsOnDestroy` | `none` | Container contents are dropped by BlockEntity#preRemoveSideEffects by default. | P1.21.5 |
| 9 | signature | `EntityRenderer#render(T entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light)` | `EntityRenderer#submit(T renderState, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera)` | Move draw calls into submit* calls on the SubmitNodeCollector and fill a render state in extractRenderState. | P1.21.9 |
| 10 | moved | `Level#isClientSide field` | `Level#isClientSide()` | The field is private, so every check has to call the method form. | P1.21.9 |
| 11 | signature | `new KeyMapping(name, keyCode, String category)` | `new KeyMapping(name, keyCode, KeyMapping.Category)` | Register a KeyMapping.Category through KeyMapping.Category#register, otherwise the mapping throws when sorted. | P1.21.9 |
| 12 | rename | `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | Rename every ResourceLocation reference to Identifier, including method names and parameters. | P1.21.11 |
| 13 | rename | `ResourceLocationException` | `IdentifierException` | Catch and import the renamed exception type. | P1.21.11 |
| 14 | moved | `net.minecraft.advancements.critereon` | `net.minecraft.advancements.criterion` | Reimport every critereon class from the corrected criterion package. | P1.21.11 |
| 15 | moved | `net.minecraft.BlockUtil, net.minecraft.FileUtil, net.minecraft.Util` | `net.minecraft.util.BlockUtil, net.minecraft.util.FileUtil, net.minecraft.util.Util` | Most utility classes moved under net.minecraft.util and need new imports. | P1.21.11 |
| 16 | moved | `net.minecraft.client.model.AllayModel` | `net.minecraft.client.model.animal.allay.AllayModel` | net.minecraft.client.model and net.minecraft.world.entity were regrouped into per-type subpackages, so reimport all model and entity classes. | P1.21.11 |
| 17 | removed | `OptionEnum` | `none` | Build options through the OptionInstance$Enum constructor with the values and codec instead of implementing OptionEnum. | P1.21.11 |
| 18 | signature | `Ingredient#CODEC and Ingredient#getItems` | `Ingredient#CODEC (formerly CODEC_NONEMPTY) and Ingredient#items` | Ingredients are HolderSet based now, so drop EMPTY and the ItemStack stream factories and use Ingredient#of. | P1.21.2 |
| 19 | signature | `Recipe#getResultItem and Recipe#getCategoryIconItem` | `RecipeDisplay through Recipe#display` | Recipes are registry entries displayed via RecipeDisplay, so move result and icon data into a SlotDisplay. | P1.21.2 |
| 20 | data-format | `item textures inside the minecraft:blocks atlas` | `a separate minecraft:items atlas, id at AtlasIds#ITEMS` | Update atlas sources and MaterialMapper use so item sprites come from the items atlas. | P1.21.11 |
| 21 | signature | `new GuiItemRenderState(..., ItemStackRenderState state, ...)` | `new GuiItemRenderState(..., TrackingItemRenderState state, ...)` | GuiItemRenderState takes a TrackingItemRenderState, which tracks the model sources used for the stack. | P1.21.7 |
| 22 | signature | `BlockBehaviour#entityInside(BlockState, Level, BlockPos, Entity)` | `BlockBehaviour#entityInside(BlockState, Level, BlockPos, Entity, boolean intersecting)` | Update the override signature to take the new boolean telling whether the entity is inside or only intersecting. | P1.21.10 |

## 1.21.11 -> 26.1

| # | Kind | From | To | Note | Source |
| --- | --- | --- | --- | --- | --- |
| 1 | behaviour | `Java 21 target` | `Java 25 target` | Raise the toolchain and IDE to Java 25 before touching any API code. | P26.1 |
| 2 | rename | `net.minecraft.client.gui.GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` | Replace GuiGraphics everywhere and drop draw, render and submit prefixes plus RenderState suffixes on its methods. | P26.1 |
| 3 | rename | `Renderable#render` | `Renderable#extractRenderState` | Render methods that submit GUI elements are now extract* calls that fill a render state. | P26.1 |
| 4 | rename | `AbstractWidget#renderWidget` | `AbstractWidget#extractWidgetRenderState` | Rename the widget draw override and submit elements instead of drawing them. | P26.1 |
| 5 | moved | `AbstractContainerScreen#renderBg` | `Screen#extractBackground` | The background draw moved up to Screen under a new name. | P26.1 |
| 6 | rename | `PlayerFaceRenderer` | `PlayerFaceExtractor` | Follow the extractor naming for the player face helper. | P26.1 |
| 7 | removed | `LootItemFunctionType (and the other loot *Type wrapper classes)` | `none; BuiltInRegistries.LOOT_FUNCTION_TYPE holds a MapCodec, and getType becomes codec()` | Register the MapCodec directly and override codec() returning it. | P26.1 |
| 8 | removed | `Entity#interactAt(Player, Vec3, InteractionHand)` | `none; Entity#interact(Player, InteractionHand, Vec3) receives the hit location` | Fold interactAt handling into interact, which is now only called inside the interaction range. | P26.1 |
| 9 | signature | `Level#random public field` | `Level#getRandom()` | The field is protected now, so all callers must use the getter. | P26.1 |
| 10 | signature | `new ChunkPos(BlockPos), new ChunkPos(long), ChunkPos#asLong` | `ChunkPos#containing(BlockPos), ChunkPos#unpack(long), ChunkPos#pack` | ChunkPos is a record now, so swap constructors for the named factories and asLong for pack. | P26.1 |
| 11 | removed | `BlockRenderDispatcher and ItemRenderer` | `none; block and item models go through the feature submission pipeline` | Replace dispatcher and renderer calls with block model render states and item model submissions. | P26.1 |
| 12 | signature | `ShapedRecipeBuilder(ItemStack result) and the other builders taking ItemStack` | `ShapedRecipeBuilder(ItemStackTemplate result); ItemLike overloads kept` | Immutability is required in recipes now, so build results from ItemStackTemplate. | P26.1 |
| 13 | behaviour | `assigning AbstractContainerScreen#imageWidth and #imageHeight` | `passing width and height to AbstractContainerScreen(menu, inventory, title, width, height)` | The fields are final and default to 176x166, so move any assignment into the super call. | P26.1 |
| 14 | rename | `net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup` | `net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab` | Fabric API dropped Yarn names, so the itemgroup package is now creativetab. | FA26.1 |
| 15 | rename | `net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper#registerKeyBinding` | `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper#registerKeyMapping` | Both the helper class and the method were renamed to the Mojang mapping names. | FA26.1 |
| 16 | rename | `ClientWorldEvents and AFTER_CLIENT_WORLD_CHANGE` | `ClientLevelEvents and AFTER_CLIENT_LEVEL_CHANGE` | World became Level across Fabric API lifecycle events. | FA26.1 |
| 17 | moved | `net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents` | `net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents` | The package moved from world to level and most members were renamed with it. | FA26.1 |
| 18 | rename | `net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents#onWorldLoad` | `net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents#onLevelLoad` | Same world to level rename on the server lifecycle events. | FA26.1 |
| 19 | rename | `net.fabricmc.fabric.api.datagen.v1.FabricDataOutput` | `net.fabricmc.fabric.api.datagen.v1.FabricPackOutput` | Data generation output is named after the pack now. | FA26.1 |
| 20 | rename | `net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry` | `net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry` | Particle factories are providers in the new naming, including the nested PendingParticleProvider type. | FA26.1 |
| 21 | rename | `net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap` | `net.fabricmc.fabric.api.client.rendering.v1.ChunkSectionLayerMap` | Block render layers are registered per chunk section layer now. | FA26.1 |
| 22 | rename | `net.fabricmc.fabric.api.datagen.v1.recipe.FabricRecipeExporter` | `net.fabricmc.fabric.api.datagen.v1.recipe.FabricRecipeOutput` | The recipe data-gen sink was renamed alongside the vanilla RecipeOutput. | FA26.1 |
| 23 | rename | `net.fabricmc.fabric.api.client.command.v2.ClientCommandManager` | `net.fabricmc.fabric.api.client.command.v2.ClientCommands` | The client command helper class was renamed, its methods are unchanged. | FA26.1 |

## 26.1 -> 26.2

| # | Kind | From | To | Note | Source |
| --- | --- | --- | --- | --- | --- |
| 1 | rename | `com.mojang.blaze3d.platform.DestFactor and SourceFactor` | `com.mojang.blaze3d.pipeline.BlendFactor plus BlendOp` | One BlendFactor enum replaces both sides, with BlendOp naming the merge operation. | P26.2 |
| 2 | rename | `TextureFormat and the per-element buffer formats of VertexFormatElement` | `GpuFormat` | Every texture and buffer format is now expressed as a GpuFormat entry such as R8_UNORM. | P26.2 |
| 3 | rename | `VertexFormat$IndexType and VertexFormat$Mode` | `IndexType and PrimitiveTopology` | Both nested enums were promoted to top level types. | P26.2 |
| 4 | removed | `the predefined VertexFormatElement constants` | `VertexFormat$Builder#addAttribute(name, GpuFormat) with at most sixteen attributes` | Build vertex formats attribute by attribute; the fixed element constants are gone. | P26.2 |
| 5 | removed | `RenderPipeline#UniformDescription and the plain sampler name list` | `BindGroupLayout attached with RenderPipeline$Builder#withBindGroupLayout` | Describe samplers and uniforms in a BindGroupLayout instead of on the pipeline. | P26.2 |
| 6 | moved | `Minecraft.getInstance().screen` | `Minecraft.getInstance().gui.screen()` | Gui now owns the current screen, so route screen access through it. | P26.2 |
| 7 | moved | `Minecraft.getInstance().gui.setOverlayMessage(...)` | `Minecraft.getInstance().gui.hud.setOverlayMessage(...)` | The heads-up display moved into its own Hud object held by Gui. | P26.2 |
| 8 | removed | `ChatFormatting inside Component building` | `Style#withColor, withBold, withItalic, withUnderlined, withStrikethrough, withObfuscated` | ChatFormatting was gutted, so express colour and formatting through Style. | P26.2 |
| 9 | signature | `FeatureRenderDispatcher#renderAllFeatures()` | `FeatureRenderDispatcher#renderAllFeatures(SubmitNodeStorage) and #prepareFrame(SubmitNodeStorage)` | Feature rendering now requires a SubmitNodeStorage for the features to submit into. | P26.2 |
| 10 | signature | `PictureInPictureRenderer constructor taking MultiBufferSource$BufferSource and renderToTexture without a collector` | `constructor without the BufferSource and renderToTexture(state, PoseStack, SubmitNodeCollector)` | Drop the buffer source parameter and submit elements through the collector. | P26.2 |
| 11 | removed | `StructureProcessorType, with StructureProcessor#getType` | `none; StructureProcessor is an interface and getType becomes codec() returning the MapCodec` | Implement StructureProcessor and register the MapCodec directly. | P26.2 |
| 12 | moved | `net.minecraft.advancements criteria, triggers and predicates in their old locations` | `net.minecraft.advancements.triggers and net.minecraft.advancements.predicates (entity predicates in .predicates.entity)` | Reimport all advancement criteria, trigger and predicate classes. | P26.2 |
| 13 | signature | `EntitySubPredicate implementations and Registries#ENTITY_SUB_PREDICATE_TYPE registrations` | `EntitySubPredicate as a functional interface with matches, registered as a Codec` | Register the predicate Codec, which is now the key of the EntityPredicate map. | P26.2 |
| 14 | removed | `HolderTagProvider, IntrinsicHolderTagsProvider and KeyTagProvider` | `none; TagsProvider takes the KeyTagProvider tag methods and TagAppender takes ResourceKeys` | Extend TagsProvider directly and add entries by ResourceKey or TagKey. | P26.2 |
| 15 | moved | `Items#WHITE_DYE and the other per-colour item fields` | `Items#DYE ColorCollection` | Colour and weathering variants are grouped into collections, so read entries from the collection. | P26.2 |
| 16 | moved | `ResourceKeys obtained from the Blocks, Items, EntityTypes, BlockEntityTypes, Fluids and Potions classes` | `BlockIds, ItemIds, EntityTypeIds, BlockEntityTypeIds, FluidIds, PotionIds, and BlockItemIds for blocks with items` | Take the ResourceKey from the matching Ids class instead of the registry holder. | P26.2 |
| 17 | moved | `com.mojang.blaze3d.opengl classes` | `a parallel API under com.mojang.blaze3d.vulkan` | Vulkan is a supported backend, so do not assume the OpenGL classes exist. | P26.2 |

## 26.2 -> 26.3

| # | Kind | From | To | Note | Source |
| --- | --- | --- | --- | --- | --- |
| 1 | rename | `NumberProvider` | `ContextFloatProvider and ContextIntProvider` | Split float and int providers and register the MapCodec to CONTEXT_FLOAT_PROVIDER_TYPE or CONTEXT_INT_PROVIDER_TYPE. | P26.3 |
| 2 | removed | `ContextAwarePredicate` | `none; criteria instances take a Holder of LootItemCondition instead` | Replace the wrapper with a Holder-wrapped condition list in trigger instances. | P26.3 |
| 3 | removed | `BlockStateProviderType, StructurePlacementType and PlacementModifierType with their type accessors` | `none; the registries hold a MapCodec and type becomes codec()` | Register the MapCodec directly and rename the accessor to codec. | P26.3 |
| 4 | removed | `Block#codec and the block type MapCodec registry` | `none` | Delete block CODEC fields and codec overrides from custom blocks. | P26.3 |
| 5 | removed | `PotionBrewing and ItemTags#BREWING_FUEL` | `BrewingRecipe under RecipeType#BREWING and DataComponents#BREWING_FUEL` | Move brewing mixes into recipes and declare fuel through the data component. | P26.3 |
| 6 | removed | `RenderSystem#outputColorTextureOverride and RenderSystem#outputDepthTextureOverride` | `none; output textures are set per RenderPass` | Create the RenderPass in a try-with-resources and specify its output textures there. | P26.3 |
| 7 | signature | `QuadCollection#getAll() passed straight to the buffer` | `ItemQuads.split(quads), submitting ItemQuads#solid and #translucent to their phases` | Wrap item quads in ItemQuads so solid and translucent quads reach the right submit phase. | P26.3 |
| 8 | rename | `RegistrySetBuilder$RegistryBootstrap` | `SingleRegistryBootstrap and MultiRegistryBootstrap` | Providers implement one of the bootstrap interfaces to feed RegistriesDatapackGenerator#forReloadableLayer. | P26.3 |
| 9 | signature | `LootTableProvider and AdvancementProvider as DataProviders` | `SingleRegistryBootstrap implementations` | Rework data generation around bootstrap providers instead of a DataProvider subclass. | P26.3 |
| 10 | signature | `AdvancementProvider taking a list of sub providers directly` | `AdvancementProvider taking List<AdvancementSubProvider$Factory>, with parent references by Identifier only` | Supply factories that build each AdvancementSubProvider from the BootstrapContext. | P26.3 |
| 11 | data-format | `loot tables, advancements and recipes loaded as non-reloadable registries, slot sources inlined` | `reloadable datapack registries listed in RegistryDataLoader#RELOADABLE_REGISTRIES` | Expect these entries to reload with the datapack and to be referenced as Holder or HolderSet. | P26.3 |
| 12 | data-format | `SlotSource values only inlined in loot tables` | `SlotSource registered in Registries#SLOT_SOURCE` | Register custom slot sources to the new registry if commands or loot tables should reference them by id. | P26.3 |
| 13 | behaviour | `LWJGL built on GLFW, with GLFW key codes` | `LWJGL built on SDL, with different scancode values and SDLScancode` | Use the constants in InputConstants or SDLScancode instead of hardcoded key codes. | P26.3 |
| 14 | rename | `LootPoolSingletonContainer` | `UniformContainerBase and its subclass SingleEntryContainerBase` | Extend SingleEntryContainerBase for one-entry pools and ExpandableContainerBase for list-like entries. | P26.3 |
| 15 | signature | `ResourceKey or direct registry value references in reloadable registry code` | `Holder-wrapped or HolderSet-wrapped registry objects` | Pick Holder or HolderSet per use case when referencing loot tables, recipes and similar reloadable entries. | P26.3 |

## Counts

| Jump | Entries |
| --- | --- |
| 1.21.1 -> 1.21.11 | 22 |
| 1.21.11 -> 26.1 | 23 |
| 26.1 -> 26.2 | 17 |
| 26.2 -> 26.3 | 15 |

## Sources (everything fetched, and what was skipped)

Used for entries:

- `P1.21.2` https://docs.neoforged.net/primer/docs/1.21.2/ - setId on blocks and items, item name/model components, Ingredient rewrite, recipe result via RecipeDisplay.
- `P1.21.4` https://docs.neoforged.net/primer/docs/1.21.4/ - Client Items JSON at `assets/<ns>/items/<path>.json`.
- `P1.21.5` https://docs.neoforged.net/primer/docs/1.21.5/ - block entity removal split (`preRemoveSideEffects`, `affectNeighborsAfterRemoval`, `dropContentsOnDestroy`).
- `P1.21.6` https://docs.neoforged.net/primer/docs/1.21.6/ - `CompoundTag` to `ValueInput`/`ValueOutput` for entity and block entity save/load.
- `P1.21.7` https://docs.neoforged.net/primer/docs/1.21.7/ - `GuiItemRenderState` takes `TrackingItemRenderState`.
- `P1.21.9` https://docs.neoforged.net/primer/docs/1.21.9/ - feature submission (`EntityRenderer#submit`, `SubmitNodeCollector`), `Level#isClientSide`, `KeyMapping$Category`.
- `P1.21.10` https://docs.neoforged.net/primer/docs/1.21.10/ - `BlockBehaviour#entityInside` extra boolean.
- `P1.21.11` https://docs.neoforged.net/primer/docs/1.21.11/ - `ResourceLocation` to `Identifier`, util and criterion package moves, model/entity subpackages, `OptionEnum`, item atlas split.
- `P26.1` https://docs.neoforged.net/primer/docs/26.1/ - Java 25, `GuiGraphicsExtractor`, extract/submit renames, loot `*Type` removal, `interactAt`, `Level#random`, `ChunkPos` record, `ItemStackTemplate`, container screen size, removals of `BlockRenderDispatcher` and `ItemRenderer`.
- `P26.2` https://docs.neoforged.net/primer/docs/26.2/ - `BlendFactor`, `GpuFormat`, vertex format rebuild, `BindGroupLayout`, Gui/Hud split, `ChatFormatting` to `Style`, `SubmitNodeStorage`, picture-in-picture, `StructureProcessor` codec, advancement package moves, `*Ids` classes, tags provider changes, Vulkan backend.
- `P26.3` https://docs.neoforged.net/primer/docs/26.3/ - `ContextFloatProvider`/`ContextIntProvider`, `ContextAwarePredicate` removal, `*Type` codec unrolling, `Block#codec` removal, brewing recipes, texture overrides, `ItemQuads`, bootstrap providers, reloadable registries, `Registries#SLOT_SOURCE`, GLFW to SDL, pool container split.
- `FA26.1` https://docs.fabricmc.net/26.1.2/develop/porting/fabric-api - the Fabric API 26.1 rename list (World to Level, itemgroup to creativetab, keybinding to keymapping and the rest of the Fabric API rows).

Fetched but contributed no entries:

- https://docs.fabricmc.net/develop/porting/ - porting index; only links to the blog, the wiki, slicedlime and the primer, no API list.
- https://docs.fabricmc.net/develop/porting/fabric-api - 404. The live page is /26.1.2/develop/porting/fabric-api, found through sitemap.xml.
- https://docs.fabricmc.net/1.21.11/develop/porting/ - same index content for 1.21.11; documents 1.21.10 to 1.21.11 and only links out.
- https://docs.fabricmc.net/26.1.2/develop/porting/ - same index content for 26.1.
- https://docs.fabricmc.net/1.21.11/develop/porting/fabric-api and https://docs.fabricmc.net/26.1/develop/porting/fabric-api - 404.
- https://docs.fabricmc.net/sitemap.xml - used to find the real fabric-api page path.
- https://docs.neoforged.net/primer/docs/1.21.3/ - 404 (the 1.21.4 primer covers 1.21.2 and 1.21.3).
- https://docs.neoforged.net/primer/docs/1.21.8/ - exists but lists one addition only, so it yielded no row.
- https://api.github.com/repos/neoforged/NeoForgePrimer and `/contents` - 404, so the primer was read from docs.neoforged.net/primer instead of GitHub.

Not fetched on purpose: https://minecraft.wiki/w/Java_Edition_protocol/Packets. It documents network packet layout, and none of the changes captured here changes a packet.

## Unverified, do not use

- Recipe result `"item"` to `"id"` in recipe JSON. Searched all primer pages and the Fabric pages: no page states that field rename. The 1.21.2 primer only says recipes became a data pack registry and that `getResultItem` gives way to `RecipeDisplay`. Do not apply this rewrite until a changelog states it.
- `assets/<ns>/items/<name>.json` replacing `assets/<ns>/models/item/<name>.json`. Only half true: 1.21.4 added the Client Items definition at `assets/<ns>/items/<name>.json`, which *points to* the model still living at `assets/<ns>/models/item/<name>.json`. Do not rewrite model paths, add the items file.
- `BlockBehaviour.Properties.setId` is verified and included, but note the page states the error only for the block and item properties, not for every `Properties` subclass.
- Jump `1.21.1 -> 1.21.11` is not a single documented jump. The primers for 1.21.7, 1.21.8 and 1.21.10 exist but list only a handful of minor changes, so that jump certainly has more breaking changes than the 22 rows captured here.

