package ${package}.fabric.api.attachment.v1;

import com.mojang.serialization.Codec;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import ${package}.Attachments;

/**
 * Fabric API's net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry on NeoForge's data attachments: each Fabric
 * attachment type is a NeoForge AttachmentType registered under the same id in NeoForge's attachment type registry
 * (in its RegisterEvent, or at once when made later while registries are open). persistent(codec) saves the value with
 * that codec under the field "value" of NeoForge's attachment data (NeoForge's save format, not Fabric's); copyOnDeath
 * keeps it on the player after death, as NeoForge does for a saved attachment and the bridge does for one that is not
 * saved; syncWith is NeoForge's sync, which sends the whole value (the size limit is not kept). The values are read and
 * written through AttachmentTarget, which convert writes for Fabric's calls on the game's objects.
 */
public final class AttachmentRegistry {

    private AttachmentRegistry() {}

    public static <A> AttachmentType<A> create(Identifier id, Consumer<Builder<A>> consumer) {
        Builder<A> builder = new BuilderImpl<>();
        consumer.accept(builder);
        return builder.buildAndRegister(id);
    }

    public static <A> AttachmentType<A> create(Identifier id) {
        return create(id, builder -> {});
    }

    public static <A> AttachmentType<A> createDefaulted(Identifier id, Supplier<A> initializer) {
        return create(id, builder -> builder.initializer(initializer));
    }

    public static <A> AttachmentType<A> createPersistent(Identifier id, Codec<A> codec) {
        return create(id, builder -> builder.persistent(codec));
    }

    @Deprecated
    public static <A> Builder<A> builder() {
        return new BuilderImpl<>();
    }

    public interface Builder<A> {
        Builder<A> persistent(Codec<A> codec);

        Builder<A> copyOnDeath();

        Builder<A> initializer(Supplier<A> initializer);

        Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec, AttachmentSyncPredicate syncPredicate);

        Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec, AttachmentSyncPredicate syncPredicate, int maxSyncSize);

        AttachmentType<A> buildAndRegister(Identifier id);
    }

    static final class BuilderImpl<A> implements Builder<A> {
        private Codec<A> codec;
        private boolean copyOnDeath;
        private Supplier<A> initializer;
        private StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec;
        private AttachmentSyncPredicate syncPredicate;

        @Override
        public Builder<A> persistent(Codec<A> codec) {
            this.codec = Objects.requireNonNull(codec, "codec cannot be null");
            return this;
        }

        @Override
        public Builder<A> copyOnDeath() {
            this.copyOnDeath = true;
            return this;
        }

        @Override
        public Builder<A> initializer(Supplier<A> initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer cannot be null");
            return this;
        }

        @Override
        public Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec, AttachmentSyncPredicate syncPredicate) {
            this.streamCodec = Objects.requireNonNull(streamCodec, "packet codec cannot be null");
            this.syncPredicate = Objects.requireNonNull(syncPredicate, "sync predicate cannot be null");
            return this;
        }

        @Override
        public Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> streamCodec, AttachmentSyncPredicate syncPredicate, int maxSyncSize) {
            return syncWith(streamCodec, syncPredicate);
        }

        @Override
        public AttachmentType<A> buildAndRegister(Identifier id) {
            Objects.requireNonNull(id, "identifier cannot be null");
            return Attachments.register(id, codec, initializer, streamCodec, syncPredicate, copyOnDeath);
        }
    }
}
