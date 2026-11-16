package ${package};

import net.minecraft.network.chat.Component;

/** NeoForge's net.neoforged.neoforge.common.TranslatableEnum: an enum constant's name for screens (its name by default). */
public interface TranslatableEnum {

    default Component getTranslatedName() {
        return Component.literal(((Enum<?>) this).name());
    }
}
