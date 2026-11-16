package ${package};

import ${package}.world.BiomeModifier;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;

/**
 * NeoForge 21.1's net.neoforged.neoforge.registries.NeoForgeRegistries (Minecraft 1.21.1), the registries this bridge gives a function on
 * Fabric: attachment types (neoforge:attachment_types), whose entries become Fabric API data attachments, and biome
 * modifier serializers (neoforge:biome_modifier_serializers), whose codecs read the data pack registry
 * neoforge:biome_modifier (world.BiomeModifierBridge applies it through Fabric API's BiomeModifications). Each registry is
 * made with Fabric API's registry builder, once for every converted mod (a second mod finds it registered). NeoForge's
 * other registries (fluid types, loot modifier serializers ...) are not mirrored: an entry in them would do nothing on
 * Fabric, so a mod that uses them names them when it compiles.
 */
public final class NeoForgeRegistries {

    private NeoForgeRegistries() {}

    public static final Registry<AttachmentType<?>> ATTACHMENT_TYPES = create(Keys.ATTACHMENT_TYPES);
    public static final Registry<MapCodec<? extends BiomeModifier>> BIOME_MODIFIER_SERIALIZERS = create(Keys.BIOME_MODIFIER_SERIALIZERS);

    static {
        ${package}.world.BiomeModifierBridge.start(BIOME_MODIFIER_SERIALIZERS);
    }

    @SuppressWarnings("unchecked")
    static synchronized <T> Registry<T> create(ResourceKey<Registry<T>> key) {
        Registry<?> existing = BuiltInRegistries.REGISTRY.get(key.location());
        if (existing != null) return (Registry<T>) existing;
        return FabricRegistryBuilder.createSimple(key).buildAndRegister();
    }

    /** The registry for a key this bridge mirrors, made if needed; null for any other key. */
    @SuppressWarnings("unchecked")
    static <T> Registry<T> registry(ResourceKey<? extends Registry<T>> key) {
        if (key.equals(Keys.ATTACHMENT_TYPES)) return (Registry<T>) ATTACHMENT_TYPES;
        if (key.equals(Keys.BIOME_MODIFIER_SERIALIZERS)) return (Registry<T>) BIOME_MODIFIER_SERIALIZERS;
        return null;
    }

    public static final class Keys {

        private Keys() {}

        public static final ResourceKey<Registry<AttachmentType<?>>> ATTACHMENT_TYPES = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("neoforge", "attachment_types"));
        public static final ResourceKey<Registry<MapCodec<? extends BiomeModifier>>> BIOME_MODIFIER_SERIALIZERS = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("neoforge", "biome_modifier_serializers"));
        public static final ResourceKey<Registry<BiomeModifier>> BIOME_MODIFIERS = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("neoforge", "biome_modifier"));
    }
}
