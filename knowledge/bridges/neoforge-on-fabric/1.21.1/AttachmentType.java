package ${package};

import com.mojang.serialization.Codec;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * NeoForge 21.1's net.neoforged.neoforge.attachment.AttachmentType (Minecraft 1.21.1) as a Fabric API data attachment. The builder takes
 * NeoForge's default value (made for the holder, as NeoForge makes it on first getData), its Codec (the attachment is
 * then persistent, saved with its holder), copyOnDeath and sync (Fabric API's syncWith; NeoForge's per-player predicate is
 * Fabric's sync predicate). It becomes Fabric's attachment type when DeferredRegister registers it into
 * neoforge:attachment_types (NeoForgeRegistries), under the same id. Not mirrored: custom IAttachmentSerializer and
 * copy handlers (a mod that uses them names them when it compiles); serialize's shouldSerialize predicate is not asked,
 * so a value it would skip is saved as well. The holder calls (getData, setData ...) go through Attachments.
 */
public final class AttachmentType<T> {

    final Function<Object, T> defaultValue;
    Codec<T> codec;
    boolean copyOnDeath;
    StreamCodec<? super RegistryFriendlyByteBuf, T> sync;
    BiPredicate<Object, ServerPlayer> sendTo;
    net.fabricmc.fabric.api.attachment.v1.AttachmentType<T> fabric;

    private AttachmentType(Builder<T> builder) {
        this.defaultValue = builder.defaultValue;
        this.codec = builder.codec;
        this.copyOnDeath = builder.copyOnDeath;
        this.sync = builder.sync;
        this.sendTo = builder.sendTo;
    }

    public static <T> Builder<T> builder(Supplier<T> defaultValueSupplier) {
        return new Builder<>(holder -> defaultValueSupplier.get());
    }

    public static <T> Builder<T> builder(Function<Object, T> defaultValueConstructor) {
        return new Builder<>(defaultValueConstructor);
    }

    /** Fabric API's attachment type for this one, made once DeferredRegister registers it. */
    net.fabricmc.fabric.api.attachment.v1.AttachmentType<T> fabric() {
        if (fabric == null) throw new IllegalStateException("Attachment type used before NeoForgeRegistries.ATTACHMENT_TYPES registered it");
        return fabric;
    }

    /** Called by DeferredRegister when it registers this type under its id. */
    synchronized void bind(ResourceLocation id) {
        if (fabric != null) return;
        fabric = AttachmentRegistry.create(id, builder -> {
            if (codec != null) builder.persistent(codec);
            if (copyOnDeath) builder.copyOnDeath();
            if (sync != null) builder.syncWith(sync, (target, player) -> sendTo.test(target, player));
        });
    }

    public static class Builder<T> {
        private final Function<Object, T> defaultValue;
        private Codec<T> codec;
        private boolean copyOnDeath;
        private StreamCodec<? super RegistryFriendlyByteBuf, T> sync;
        private BiPredicate<Object, ServerPlayer> sendTo;

        private Builder(Function<Object, T> defaultValue) {
            this.defaultValue = defaultValue;
        }

        public Builder<T> serialize(Codec<T> codec) {
            Objects.requireNonNull(codec);
            if (this.codec != null) throw new IllegalStateException("Serializer already set");
            this.codec = codec;
            return this;
        }

        public Builder<T> serialize(Codec<T> codec, Predicate<? super T> shouldSerialize) {
            return serialize(codec);
        }

        public Builder<T> copyOnDeath() {
            if (codec == null) throw new IllegalStateException("copyOnDeath requires a serializer");
            copyOnDeath = true;
            return this;
        }

        public Builder<T> sync(StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec) {
            return sync((holder, to) -> true, streamCodec);
        }

        public Builder<T> sync(BiPredicate<Object, ServerPlayer> sendToPlayer, StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec) {
            this.sendTo = Objects.requireNonNull(sendToPlayer);
            this.sync = Objects.requireNonNull(streamCodec);
            return this;
        }

        public AttachmentType<T> build() {
            return new AttachmentType<>(this);
        }
    }
}
