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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * NeoForge's net.neoforged.neoforge.registries.DeferredRegister on Fabric. Entries are collected as NeoForge collects
 * them; register(IEventBus) hands this register to the bridge, which registers every collected entry when the mod's
 * entrypoint ends, registry by registry in the game's own registry order (blocks before items), as NeoForge's
 * RegisterEvent does. Blocks and items get their registry id in their properties, as NeoForge's helpers set it.
 */
public class DeferredRegister<T> {

    static final List<DeferredRegister<?>> PENDING = new ArrayList<>();

    final ResourceKey<? extends Registry<T>> registryKey;
    final String namespace;
    final Map<DeferredHolder<T, ? extends T>, Function<Identifier, ? extends T>> entries = new LinkedHashMap<>();
    boolean registered;

    public static <T> DeferredRegister<T> create(Registry<T> registry, String namespace) {
        return new DeferredRegister<>(registry.key(), namespace);
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        return new DeferredRegister<>(key, namespace);
    }

    public static <B> DeferredRegister<B> create(Identifier registryName, String namespace) {
        return new DeferredRegister<>(ResourceKey.createRegistryKey(registryName), namespace);
    }

    public static Items createItems(String namespace) {
        return new Items(namespace);
    }

    public static Blocks createBlocks(String namespace) {
        return new Blocks(namespace);
    }

    protected DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String namespace) {
        this.registryKey = registryKey;
        this.namespace = namespace;
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> supplier) {
        return register(name, key -> supplier.get());
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Function<Identifier, ? extends I> function) {
        if (registered) throw new IllegalStateException("Cannot register new entries to DeferredRegister after it was registered");
        Identifier id = Identifier.fromNamespaceAndPath(namespace, name);
        DeferredHolder<T, I> holder = createHolder(registryKey, id);
        if (entries.putIfAbsent(holder, function) != null) throw new IllegalArgumentException("Duplicate registration " + name);
        return holder;
    }

    protected <I extends T> DeferredHolder<T, I> createHolder(ResourceKey<? extends Registry<T>> registryKey, Identifier id) {
        return DeferredHolder.create(ResourceKey.create(registryKey, id));
    }

    public TagKey<T> createTagKey(String path) {
        return createTagKey(Identifier.fromNamespaceAndPath(namespace, path));
    }

    public TagKey<T> createTagKey(Identifier id) {
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

    public Identifier getRegistryName() {
        return registryKey.identifier();
    }

    public String getNamespace() {
        return namespace;
    }

    @SuppressWarnings("unchecked")
    public Supplier<Registry<T>> getRegistry() {
        return () -> (Registry<T>) BuiltInRegistries.REGISTRY.getValue(registryKey.identifier());
    }

    /** Registers every collected entry in the game's registry; called once by the bridge. */
    void registerAll() {
        if (registered) return;
        registered = true;
        Registry<T> registry = getRegistry().get();
        if (registry == null) throw new IllegalStateException("No registry " + registryKey.identifier() + " for " + namespace);
        for (var entry : entries.entrySet()) {
            ResourceKey<T> key = entry.getKey().getKey();
            Registry.register(registry, key, entry.getValue().apply(key.identifier()));
            entry.getKey().bind();
        }
    }

    /** Every handed-over register, in the game's own registry order, then any registry the game does not list. */
    static void registerPending() {
        List<DeferredRegister<?>> left = new ArrayList<>(PENDING);
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            for (DeferredRegister<?> register : PENDING) if (register.registryKey.equals(registry.key())) {
                register.registerAll();
                left.remove(register);
            }
        }
        for (DeferredRegister<?> register : left) register.registerAll();
        PENDING.clear();
    }

    /** NeoForge's DeferredRegister.Blocks: DeferredBlock holders, and blocks whose properties carry their id. */
    public static class Blocks extends DeferredRegister<Block> {

        protected Blocks(String namespace) {
            super(Registries.BLOCK, namespace);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B extends Block> DeferredBlock<B> register(String name, Function<Identifier, ? extends B> function) {
            return (DeferredBlock<B>) super.register(name, function);
        }

        @Override
        public <B extends Block> DeferredBlock<B> register(String name, Supplier<? extends B> supplier) {
            return register(name, key -> supplier.get());
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> function, Supplier<BlockBehaviour.Properties> properties) {
            return register(name, key -> function.apply(properties.get().setId(ResourceKey.create(Registries.BLOCK, key))));
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> function, UnaryOperator<BlockBehaviour.Properties> properties) {
            return registerBlock(name, function, () -> properties.apply(BlockBehaviour.Properties.of()));
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> function) {
            return registerBlock(name, function, () -> BlockBehaviour.Properties.of());
        }

        public DeferredBlock<Block> registerSimpleBlock(String name, Supplier<BlockBehaviour.Properties> properties) {
            return registerBlock(name, Block::new, properties);
        }

        public DeferredBlock<Block> registerSimpleBlock(String name, UnaryOperator<BlockBehaviour.Properties> properties) {
            return registerBlock(name, Block::new, properties);
        }

        public DeferredBlock<Block> registerSimpleBlock(String name) {
            return registerBlock(name, Block::new);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <I extends Block> DeferredHolder<Block, I> createHolder(ResourceKey<? extends Registry<Block>> registryKey, Identifier id) {
            return (DeferredHolder<Block, I>) DeferredBlock.createBlock(ResourceKey.create(registryKey, id));
        }
    }

    /** NeoForge's DeferredRegister.Items: DeferredItem holders, and items whose properties carry their id. */
    public static class Items extends DeferredRegister<Item> {

        protected Items(String namespace) {
            super(Registries.ITEM, namespace);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <I extends Item> DeferredItem<I> register(String name, Function<Identifier, ? extends I> function) {
            return (DeferredItem<I>) super.register(name, function);
        }

        @Override
        public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> supplier) {
            return register(name, key -> supplier.get());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block, Supplier<Item.Properties> properties) {
            return registerItem(name, props -> new BlockItem(block.get(), props), () -> properties.get().useBlockDescriptionPrefix());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block, UnaryOperator<Item.Properties> properties) {
            return registerSimpleBlockItem(name, block, () -> properties.apply(new Item.Properties()));
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block) {
            return registerSimpleBlockItem(name, block, () -> new Item.Properties());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block, Supplier<Item.Properties> properties) {
            return registerSimpleBlockItem(block.unwrapKey().orElseThrow().identifier().getPath(), block::value, properties);
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block, UnaryOperator<Item.Properties> properties) {
            return registerSimpleBlockItem(block, () -> properties.apply(new Item.Properties()));
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block) {
            return registerSimpleBlockItem(block, () -> new Item.Properties());
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> function, Supplier<Item.Properties> properties) {
            return register(name, key -> function.apply(properties.get().setId(ResourceKey.create(Registries.ITEM, key))));
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> function, UnaryOperator<Item.Properties> properties) {
            return registerItem(name, function, () -> properties.apply(new Item.Properties()));
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> function) {
            return registerItem(name, function, () -> new Item.Properties());
        }

        public DeferredItem<Item> registerSimpleItem(String name, Supplier<Item.Properties> properties) {
            return registerItem(name, Item::new, properties);
        }

        public DeferredItem<Item> registerSimpleItem(String name, UnaryOperator<Item.Properties> properties) {
            return registerItem(name, Item::new, properties);
        }

        public DeferredItem<Item> registerSimpleItem(String name) {
            return registerItem(name, Item::new);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <I extends Item> DeferredHolder<Item, I> createHolder(ResourceKey<? extends Registry<Item>> registryKey, Identifier id) {
            return (DeferredHolder<Item, I>) DeferredItem.createItem(ResourceKey.create(registryKey, id));
        }
    }
}
