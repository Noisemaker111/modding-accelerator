package ${package};

import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;

/**
 * NeoForge's net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs: stream codecs the game lacks, written on the
 * game's FriendlyByteBuf as NeoForge writes them, so the bytes are the same. Difference: connectionAware always uses
 * the codec for other connections (otherCodec), as NeoForge does for a connection to a game that is not NeoForge; on
 * Fabric neither end has NeoForge's own stream extensions.
 */
public final class NeoForgeStreamCodecs {

    private NeoForgeStreamCodecs() {}

    public static final StreamCodec<FriendlyByteBuf, byte[]> UNBOUNDED_BYTE_ARRAY = new StreamCodec<>() {
        @Override
        public byte[] decode(FriendlyByteBuf buf) {
            return buf.readByteArray();
        }

        @Override
        public void encode(FriendlyByteBuf buf, byte[] data) {
            buf.writeByteArray(data);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, ChunkPos> CHUNK_POS = new StreamCodec<>() {
        @Override
        public ChunkPos decode(FriendlyByteBuf buf) {
            return buf.readChunkPos();
        }

        @Override
        public void encode(FriendlyByteBuf buf, ChunkPos pos) {
            buf.writeChunkPos(pos);
        }
    };

    public static <B, V> StreamCodec<B, V> lazy(Supplier<StreamCodec<B, V>> streamCodecSupplier) {
        return new LazyStreamCodec<>(streamCodecSupplier);
    }

    private static class LazyStreamCodec<B, V> implements StreamCodec<B, V> {

        private final Lazy<StreamCodec<B, V>> delegate;

        LazyStreamCodec(Supplier<StreamCodec<B, V>> streamCodecSupplier) {
            delegate = Lazy.of(streamCodecSupplier);
        }

        @Override
        public void encode(B buf, V value) {
            delegate.get().encode(buf, value);
        }

        @Override
        public V decode(B buf) {
            return delegate.get().decode(buf);
        }
    }

    public static <B extends FriendlyByteBuf, V extends Enum<V>> StreamCodec<B, V> enumCodec(Class<V> enumClass) {
        return new StreamCodec<>() {
            @Override
            public V decode(B buf) {
                return buf.readEnum(enumClass);
            }

            @Override
            public void encode(B buf, V value) {
                buf.writeEnum(value);
            }
        };
    }

    public static <B extends FriendlyByteBuf> StreamCodec<B, ResourceKey<? extends Registry<?>>> registryKey() {
        return new StreamCodec<>() {
            @Override
            public ResourceKey<? extends Registry<?>> decode(B buf) {
                return ResourceKey.createRegistryKey(buf.readIdentifier());
            }

            @Override
            public void encode(B buf, ResourceKey<? extends Registry<?>> value) {
                buf.writeIdentifier(value.identifier());
            }
        };
    }

    public static <V> StreamCodec<RegistryFriendlyByteBuf, V> connectionAware(StreamCodec<? super RegistryFriendlyByteBuf, V> neoForgeCodec, StreamCodec<? super RegistryFriendlyByteBuf, V> otherCodec) {
        return new StreamCodec<>() {
            @Override
            public V decode(RegistryFriendlyByteBuf buf) {
                return otherCodec.decode(buf);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, V value) {
                otherCodec.encode(buf, value);
            }
        };
    }

    public static <B, V> StreamCodec<B, V> uncheckedUnit(final V defaultValue) {
        return new StreamCodec<>() {
            @Override
            public V decode(B buf) {
                return defaultValue;
            }

            @Override
            public void encode(B buf, V value) {}
        };
    }
}
