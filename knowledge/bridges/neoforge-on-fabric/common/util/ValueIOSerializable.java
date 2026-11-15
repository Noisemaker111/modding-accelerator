package ${package}.common.util;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** NeoForge's net.neoforged.neoforge.common.util.ValueIOSerializable: an object saved with its owner's data. */
public interface ValueIOSerializable {

    void serialize(ValueOutput output);

    void deserialize(ValueInput input);
}
