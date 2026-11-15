package ${package};

import com.mojang.datafixers.util.Function7;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;

/**
 * NeoForge 21.1's net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs: stream codecs the game lacks, written on the
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
                return ResourceKey.createRegistryKey(buf.readResourceLocation());
            }

            @Override
            public void encode(B buf, ResourceKey<? extends Registry<?>> value) {
                buf.writeResourceLocation(value.location());
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

    public static <B, C, T1, T2, T3, T4, T5, T6, T7> StreamCodec<B, C> composite(
        final StreamCodec<? super B, T1> codec1,
        final Function<C, T1> getter1,
        final StreamCodec<? super B, T2> codec2,
        final Function<C, T2> getter2,
        final StreamCodec<? super B, T3> codec3,
        final Function<C, T3> getter3,
        final StreamCodec<? super B, T4> codec4,
        final Function<C, T4> getter4,
        final StreamCodec<? super B, T5> codec5,
        final Function<C, T5> getter5,
        final StreamCodec<? super B, T6> codec6,
        final Function<C, T6> getter6,
        final StreamCodec<? super B, T7> codec7,
        final Function<C, T7> getter7,
        final Function7<T1, T2, T3, T4, T5, T6, T7, C> factory
    ) {
        return new StreamCodec<>() {
            @Override
            public C decode(B buf) {
                T1 t1 = codec1.decode(buf);
                T2 t2 = codec2.decode(buf);
                T3 t3 = codec3.decode(buf);
                T4 t4 = codec4.decode(buf);
                T5 t5 = codec5.decode(buf);
                T6 t6 = codec6.decode(buf);
                T7 t7 = codec7.decode(buf);
                return factory.apply(t1, t2, t3, t4, t5, t6, t7);
            }

            @Override
            public void encode(B buf, C value) {
                codec1.encode(buf, getter1.apply(value));
                codec2.encode(buf, getter2.apply(value));
                codec3.encode(buf, getter3.apply(value));
                codec4.encode(buf, getter4.apply(value));
                codec5.encode(buf, getter5.apply(value));
                codec6.encode(buf, getter6.apply(value));
                codec7.encode(buf, getter7.apply(value));
            }
        };
    }
}
