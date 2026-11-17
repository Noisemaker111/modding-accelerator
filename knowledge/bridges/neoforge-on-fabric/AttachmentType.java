package ${package};

import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import ${package}.common.util.ValueIOSerializable;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * NeoForge's net.neoforged.neoforge.attachment.AttachmentType as a Fabric API data attachment. The builder takes
 * NeoForge's default value (made for the holder, as NeoForge makes it on first getData), its MapCodec (the attachment is
 * then persistent, saved with its holder), copyOnDeath and sync (Fabric API's syncWith; NeoForge's per-player predicate is
 * Fabric's sync predicate). It becomes Fabric's attachment type when DeferredRegister registers it into
 * neoforge:attachment_types (NeoForgeRegistries), under the same id. Not mirrored: custom IAttachmentSerializer and
 * copy handlers (a mod that uses them names them when it compiles); serialize's shouldSerialize predicate is not asked,
 * so a value it would skip is saved as well. The holder calls (getData, setData ...) go through Attachments.
 */
public final class AttachmentType<T> {

    final Function<IAttachmentHolder, T> defaultValue;
    MapCodec<T> codec;
    boolean copyOnDeath;
    StreamCodec<? super RegistryFriendlyByteBuf, T> sync;
    BiPredicate<Object, ServerPlayer> sendTo;

    /** The default value for a holder (an entity, block entity, level or chunk) that has none yet. */
    T create(Object holder) {
        return defaultValue.apply((IAttachmentHolder) holder);
    }

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

    public static <T> Builder<T> builder(Function<IAttachmentHolder, T> defaultValueConstructor) {
        return new Builder<>(defaultValueConstructor);
    }

    /**
     * NeoForge's serializable(...): the value writes and reads itself with the game's value IO, saved as a compound tag
     * with the running server's registries (an item stack in it needs them).
     */
    public static <T extends ValueIOSerializable> Builder<T> serializable(Supplier<T> defaultValueSupplier) {
        return serializable(holder -> defaultValueSupplier.get());
    }

    public static <T extends ValueIOSerializable> Builder<T> serializable(Function<IAttachmentHolder, T> defaultValueConstructor) {
        return builder(defaultValueConstructor).serialize(MapCodec.assumeMapUnsafe(CompoundTag.CODEC.xmap(
            tag -> {
                T value = defaultValueConstructor.apply(null);
                value.deserialize(TagValueInput.create(ProblemReporter.DISCARDING, registries(), tag));
                return value;
            },
            value -> {
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries());
                value.serialize(output);
                return output.buildResult();
            }
        )));
    }

    private static HolderLookup.Provider registries() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? RegistryAccess.EMPTY : server.registryAccess();
    }

    /** Fabric API's attachment type for this one, made once DeferredRegister registers it. */
    net.fabricmc.fabric.api.attachment.v1.AttachmentType<T> fabric() {
        if (fabric == null) throw new IllegalStateException("Attachment type used before NeoForgeRegistries.ATTACHMENT_TYPES registered it");
        return fabric;
    }

    /** Called by DeferredRegister when it registers this type under its id. */
    synchronized void bind(Identifier id) {
        if (fabric != null) return;
        fabric = AttachmentRegistry.create(id, builder -> {
            if (codec != null) builder.persistent(codec.codec());
            if (copyOnDeath) builder.copyOnDeath();
            if (sync != null) builder.syncWith(sync, (target, player) -> sendTo.test(target, player));
        });
    }

    public static class Builder<T> {
        private final Function<IAttachmentHolder, T> defaultValue;
        private MapCodec<T> codec;
        private boolean copyOnDeath;
        private StreamCodec<? super RegistryFriendlyByteBuf, T> sync;
        private BiPredicate<Object, ServerPlayer> sendTo;

        private Builder(Function<IAttachmentHolder, T> defaultValue) {
            this.defaultValue = defaultValue;
        }

        public Builder<T> serialize(MapCodec<T> codec) {
            Objects.requireNonNull(codec);
            if (this.codec != null) throw new IllegalStateException("Serializer already set");
            this.codec = codec;
            return this;
        }

        public Builder<T> serialize(MapCodec<T> codec, Predicate<? super T> shouldSerialize) {
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
