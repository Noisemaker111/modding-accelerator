package ${package};

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * NeoForge's net.neoforged.neoforge.common.util.ValueIOSerializable: an object that writes itself to and reads itself
 * from the game's ValueOutput and ValueInput. A plain interface, the same as NeoForge's; nothing in the bridge calls it.
 */
public interface ValueIOSerializable {

    void serialize(ValueOutput output);

    void deserialize(ValueInput input);
}
