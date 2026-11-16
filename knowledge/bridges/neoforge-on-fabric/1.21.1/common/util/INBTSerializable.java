package ${package}.common.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;

/** NeoForge 21.1's net.neoforged.neoforge.common.util.INBTSerializable: an object saved as, and read back from, one tag. */
public interface INBTSerializable<T extends Tag> {

    T serializeNBT(HolderLookup.Provider provider);

    void deserializeNBT(HolderLookup.Provider provider, T nbt);
}
