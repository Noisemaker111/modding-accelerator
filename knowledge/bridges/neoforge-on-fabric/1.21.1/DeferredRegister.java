package ${package};

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * NeoForge 21.1's net.neoforged.neoforge.registries.DeferredRegister on Fabric (Minecraft 1.21.1). Entries are
 * collected as NeoForge collects them; register(IEventBus) hands this register to the bridge, which registers every
 * collected entry when the mod's entrypoint ends, registry by registry in the game's own registry order (blocks before
 * items), as NeoForge's RegisterEvent does.
 */
public class DeferredRegister<T> {

    static final List<DeferredRegister<?>> PENDING = new ArrayList<>();

    final ResourceKey<? extends Registry<T>> registryKey;
    final String namespace;
    final Map<DeferredHolder<T, ? extends T>, Function<ResourceLocation, ? extends T>> entries = new LinkedHashMap<>();
    boolean registered;

    /**
     * NeoForge's addAlias: worlds saved with the old id load as the new one. Fabric's registries keep no aliases, so the
     * alias is only recorded (ALIASES); an old id in a world reads as missing, as on Fabric.
     */
    public void addAlias(net.minecraft.resources.ResourceLocation from, net.minecraft.resources.ResourceLocation to) {
        ALIASES.put(from, to);
    }

    /** The aliases mods asked for (addAlias), old id to new. */
    public static final java.util.Map<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> ALIASES = new java.util.concurrent.ConcurrentHashMap<>();

    public static <T> DeferredRegister<T> create(Registry<T> registry, String namespace) {
        return new DeferredRegister<>(registry.key(), namespace);
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        return new DeferredRegister<>(key, namespace);
    }

    public static <B> DeferredRegister<B> create(ResourceLocation registryName, String namespace) {
        return new DeferredRegister<>(ResourceKey.createRegistryKey(registryName), namespace);
    }

    public static Items createItems(String namespace) {
        return new Items(namespace);
    }

    public static Blocks createBlocks(String namespace) {
        return new Blocks(namespace);
    }

    public static DataComponents createDataComponents(String namespace) {
        return new DataComponents(namespace);
    }

    public static DataComponents createDataComponents(ResourceKey<Registry<DataComponentType<?>>> registry, String namespace) {
        return new DataComponents(registry, namespace);
    }

    protected DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String namespace) {
        this.registryKey = registryKey;
        this.namespace = namespace;
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> supplier) {
        return register(name, key -> supplier.get());
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Function<ResourceLocation, ? extends I> function) {
        if (registered) throw new IllegalStateException("Cannot register new entries to DeferredRegister after it was registered");
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        DeferredHolder<T, I> holder = createHolder(registryKey, id);
        if (entries.putIfAbsent(holder, function) != null) throw new IllegalArgumentException("Duplicate registration " + name);
        return holder;
    }

    protected <I extends T> DeferredHolder<T, I> createHolder(ResourceKey<? extends Registry<T>> registryKey, ResourceLocation id) {
        return DeferredHolder.create(ResourceKey.create(registryKey, id));
    }

    public TagKey<T> createTagKey(String path) {
        return createTagKey(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public TagKey<T> createTagKey(ResourceLocation id) {
        return TagKey.create(registryKey, id);
    }

    public void register(IEventBus bus) {
        if (!PENDING.contains(this)) PENDING.add(this);
    }

    public Collection<DeferredHolder<T, ? extends T>> getEntries() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public ResourceKey<? extends Registry<T>> getRegistryKey() {
        return registryKey;
    }

    public ResourceLocation getRegistryName() {
        return registryKey.location();
    }

    public String getNamespace() {
        return namespace;
    }

    @SuppressWarnings("unchecked")
    public Supplier<Registry<T>> getRegistry() {
        return () -> (Registry<T>) BuiltInRegistries.REGISTRY.get(registryKey.location());
    }

    /** Registers every collected entry in the game's registry; called once by the bridge. */
    void registerAll() {
        if (registered) return;
        registered = true;
        Registry<T> registry = getRegistry().get();
        // NeoForge's own registries the bridge mirrors (attachment types) are made on first use.
        if (registry == null) registry = NeoForgeRegistries.registry(registryKey);
        if (registry == null) throw new IllegalStateException("No registry " + registryKey.location() + " for " + namespace);
        for (var entry : entries.entrySet()) {
            ResourceKey<T> key = entry.getKey().getKey();
            T value = entry.getValue().apply(key.location());
            Registry.register(registry, key, value);
            // A NeoForge attachment type becomes Fabric API's attachment type of the same id.
            if (value instanceof AttachmentType<?> attachment) attachment.bind(key.location());
            entry.getKey().bind();
        }
    }

    /** Every handed-over register, in the game's own registry order, each registry followed by its RegisterEvent, then any registry the game does not list. */
    static void registerPending() {
        List<DeferredRegister<?>> left = new ArrayList<>(PENDING);
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            for (DeferredRegister<?> register : PENDING) if (register.registryKey.equals(registry.key())) {
                register.registerAll();
                left.remove(register);
            }
            RegisterEvent.post(registry);
        }
        for (DeferredRegister<?> register : left) register.registerAll();
        PENDING.clear();
    }

    /** NeoForge 21.1's DeferredRegister.Blocks: DeferredBlock holders. */
    public static class Blocks extends DeferredRegister<Block> {

        protected Blocks(String namespace) {
            super(Registries.BLOCK, namespace);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B extends Block> DeferredBlock<B> register(String name, Function<ResourceLocation, ? extends B> function) {
            return (DeferredBlock<B>) super.register(name, function);
        }

        @Override
        public <B extends Block> DeferredBlock<B> register(String name, Supplier<? extends B> supplier) {
            return register(name, key -> supplier.get());
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> function, BlockBehaviour.Properties properties) {
            return register(name, key -> function.apply(properties));
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> function) {
            return registerBlock(name, function, BlockBehaviour.Properties.of());
        }

        public DeferredBlock<Block> registerSimpleBlock(String name, BlockBehaviour.Properties properties) {
            return registerBlock(name, Block::new, properties);
        }

        public DeferredBlock<Block> registerSimpleBlock(String name) {
            return registerSimpleBlock(name, BlockBehaviour.Properties.of());
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <I extends Block> DeferredHolder<Block, I> createHolder(ResourceKey<? extends Registry<Block>> registryKey, ResourceLocation id) {
            return (DeferredHolder<Block, I>) DeferredBlock.createBlock(ResourceKey.create(registryKey, id));
        }
    }

    /** NeoForge 21.1's DeferredRegister.Items: DeferredItem holders. */
    public static class Items extends DeferredRegister<Item> {

        protected Items(String namespace) {
            super(Registries.ITEM, namespace);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <I extends Item> DeferredItem<I> register(String name, Function<ResourceLocation, ? extends I> function) {
            return (DeferredItem<I>) super.register(name, function);
        }

        @Override
        public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> supplier) {
            return register(name, key -> supplier.get());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block, Item.Properties properties) {
            return register(name, key -> new BlockItem(block.get(), properties));
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block) {
            return registerSimpleBlockItem(name, block, new Item.Properties());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block, Item.Properties properties) {
            return registerSimpleBlockItem(block.unwrapKey().orElseThrow().location().getPath(), block::value, properties);
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block) {
            return registerSimpleBlockItem(block, new Item.Properties());
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> function, Item.Properties properties) {
            return register(name, key -> function.apply(properties));
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> function) {
            return registerItem(name, function, new Item.Properties());
        }

        public DeferredItem<Item> registerSimpleItem(String name, Item.Properties properties) {
            return registerItem(name, Item::new, properties);
        }

        public DeferredItem<Item> registerSimpleItem(String name) {
            return registerItem(name, Item::new);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <I extends Item> DeferredHolder<Item, I> createHolder(ResourceKey<? extends Registry<Item>> registryKey, ResourceLocation id) {
            return (DeferredHolder<Item, I>) DeferredItem.createItem(ResourceKey.create(registryKey, id));
        }
    }

    /** NeoForge 21.1's DeferredRegister.DataComponents: data component types from their builder. */
    public static class DataComponents extends DeferredRegister<DataComponentType<?>> {

        protected DataComponents(ResourceKey<Registry<DataComponentType<?>>> registry, String namespace) {
            super(registry, namespace);
        }

        protected DataComponents(String namespace) {
            super(Registries.DATA_COMPONENT_TYPE, namespace);
        }

        public <D> DeferredHolder<DataComponentType<?>, DataComponentType<D>> registerComponentType(String name, UnaryOperator<DataComponentType.Builder<D>> builder) {
            return register(name, () -> builder.apply(DataComponentType.builder()).build());
        }
    }
}
