package ${package};

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * NeoForge's net.neoforged.neoforge.client.gui.ConfigurationScreen, which builds a screen from a mod's ModConfigSpec in
 * NeoForge's mod list. Fabric has no mod list to open it from, so here it is only the type a factory names.
 */
public class ConfigurationScreen extends Screen {

    final Screen parent;

    public ConfigurationScreen(ModContainer container, Screen parent) {
        super(Component.literal(container.getModId()));
        this.parent = parent;
    }
}
