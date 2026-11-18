package ${package}.datamaps;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;

/**
 * NeoForge's net.neoforged.neoforge.registries.datamaps.DataMapType: a data map of a registry, read from
 * data/NAMESPACE/data_maps/REGISTRY/PATH.json (DataMaps loads it on Fabric) and read back with holder.getData(type).
 */
public class DataMapType<R, T> {

    private final ResourceKey<Registry<R>> registryKey;
    private final ResourceLocation id;
    private final Codec<T> codec;
    private final Codec<T> networkCodec;
    private final boolean mandatorySync;

    DataMapType(ResourceKey<Registry<R>> registryKey, ResourceLocation id, Codec<T> codec, Codec<T> networkCodec, boolean mandatorySync) {
        this.registryKey = registryKey;
        this.id = id;
        this.codec = codec;
        this.networkCodec = networkCodec;
        this.mandatorySync = mandatorySync;
    }

    public static <T, R> Builder<T, R> builder(ResourceLocation id, ResourceKey<Registry<R>> registry, Codec<T> codec) {
        return new Builder<>(registry, id, codec);
    }

    public ResourceKey<Registry<R>> registryKey() {
        return this.registryKey;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public Codec<T> codec() {
        return this.codec;
    }

    public Codec<T> networkCodec() {
        return this.networkCodec;
    }

    public boolean mandatorySync() {
        return this.mandatorySync;
    }

    @Override
    public String toString() {
        return "DataMapType[" + this.registryKey.location() + "/" + this.id + "]";
    }

    public static class Builder<T, R> {

        private final ResourceKey<Registry<R>> registryKey;
        private final ResourceLocation id;
        private final Codec<T> codec;
        private Codec<T> networkCodec;
        private boolean mandatory;

        Builder(ResourceKey<Registry<R>> registryKey, ResourceLocation id, Codec<T> codec) {
            this.registryKey = registryKey;
            this.id = id;
            this.codec = codec;
        }

        public Builder<T, R> synced(Codec<T> networkCodec, boolean mandatory) {
            this.networkCodec = networkCodec;
            this.mandatory = mandatory;
            return this;
        }

        public DataMapType<R, T> build() {
            return new DataMapType<>(this.registryKey, this.id, this.codec, this.networkCodec, this.mandatory);
        }
    }
}
