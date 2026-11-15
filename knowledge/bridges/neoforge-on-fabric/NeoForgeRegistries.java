package ${package};

import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * NeoForge's net.neoforged.neoforge.registries.NeoForgeRegistries, the registries this bridge gives a function on
 * Fabric: attachment types (neoforge:attachment_types), whose entries become Fabric API data attachments. The registry is
 * made with Fabric API's registry builder, once for every converted mod (a second mod finds it registered). NeoForge's
 * other registries (fluid types, loot modifier serializers, biome modifiers ...) are not mirrored: an entry in them would
 * do nothing on Fabric, so a mod that uses them names them when it compiles.
 */
public final class NeoForgeRegistries {

    private NeoForgeRegistries() {}

    public static final Registry<AttachmentType<?>> ATTACHMENT_TYPES = create(Keys.ATTACHMENT_TYPES);

    @SuppressWarnings("unchecked")
    static synchronized <T> Registry<T> create(ResourceKey<Registry<T>> key) {
        Registry<?> existing = BuiltInRegistries.REGISTRY.getValue(key.identifier());
        if (existing != null) return (Registry<T>) existing;
        return FabricRegistryBuilder.create(key).buildAndRegister();
    }

    /** The registry for a key this bridge mirrors, made if needed; null for any other key. */
    @SuppressWarnings("unchecked")
    static <T> Registry<T> registry(ResourceKey<? extends Registry<T>> key) {
        return key.equals(Keys.ATTACHMENT_TYPES) ? (Registry<T>) ATTACHMENT_TYPES : null;
    }

    public static final class Keys {

        private Keys() {}

        public static final ResourceKey<Registry<AttachmentType<?>>> ATTACHMENT_TYPES = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("neoforge", "attachment_types"));
    }
}
