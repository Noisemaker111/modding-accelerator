package ${package};

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import ${package}.fabric.api.attachment.v1.AttachmentSyncPredicate;
import ${package}.fabric.api.attachment.v1.AttachmentType;

/**
 * The bridge's Fabric attachment types on NeoForge's: each is a NeoForge AttachmentType registered in NeoForge's
 * attachment type registry under its Fabric id, in the RegisterEvent for that registry, or at once when it is made
 * after that event while the registries are still open (mod loading's registration phase).
 */
public final class Attachments {

    private static final Map<Identifier, net.neoforged.neoforge.attachment.AttachmentType<?>> PENDING = new LinkedHashMap<>();
    private static final List<AttachmentType<?>> COPIED_BY_BRIDGE = new ArrayList<>();
    private static boolean open;

    private Attachments() {}

    static void common(IEventBus modBus) {
        modBus.addListener(RegisterEvent.class, event -> {
            if (!event.getRegistryKey().equals(NeoForgeRegistries.Keys.ATTACHMENT_TYPES)) return;
            synchronized (Attachments.class) {
                for (var e : PENDING.entrySet()) {
                    net.neoforged.neoforge.attachment.AttachmentType<?> type = e.getValue();
                    event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, e.getKey(), () -> type);
                }
                PENDING.clear();
                open = true;
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.Clone.class, Attachments::copyOnDeath);
    }

    /** Makes and registers one type (AttachmentRegistry's builder calls this). */
    public static synchronized <A> AttachmentType<A> register(
        Identifier id,
        Codec<A> codec,
        Supplier<A> initializer,
        StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec,
        AttachmentSyncPredicate syncPredicate,
        boolean copyOnDeath
    ) {
        Supplier<A> defaults = () -> {
            if (initializer == null) throw new IllegalArgumentException("Single-argument getAttachedOrCreate is reserved for attachment types with default initializers");
            return initializer.get();
        };
        net.neoforged.neoforge.attachment.AttachmentType.Builder<A> builder = net.neoforged.neoforge.attachment.AttachmentType.builder(defaults);
        if (codec != null) builder.serialize(codec);
        if (codec != null && copyOnDeath) builder.copyOnDeath();
        // NeoForge 21.1 does not sync attachments (nor does Fabric API 1.21.1): a sync codec is kept on the type only.
        net.neoforged.neoforge.attachment.AttachmentType<A> neoforge = builder.build();
        Type<A> type = new Type<>(id, codec, initializer, streamCodec != null, copyOnDeath, neoforge);
        if (copyOnDeath && codec == null) COPIED_BY_BRIDGE.add(type);
        if (open) Registry.register(NeoForgeRegistries.ATTACHMENT_TYPES, id, neoforge);
        else PENDING.put(id, neoforge);
        return type;
    }

    /** A copyOnDeath type NeoForge cannot copy (it copies saved attachments only): the bridge copies it to the respawned player. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void copyOnDeath(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        for (AttachmentType type : COPIED_BY_BRIDGE) {
            Object value = event.getOriginal().getExistingDataOrNull(type.neoforge());
            if (value != null) event.getEntity().setData(type.neoforge(), value);
        }
    }

    record Type<A>(
        Identifier identifier,
        Codec<A> persistenceCodec,
        Supplier<A> initializer,
        boolean isSynced,
        boolean copyOnDeath,
        net.neoforged.neoforge.attachment.AttachmentType<A> neoforge
    ) implements AttachmentType<A> {}
}
