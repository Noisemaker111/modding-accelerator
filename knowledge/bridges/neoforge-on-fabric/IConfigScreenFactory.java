package ${package};

import net.minecraft.client.gui.screens.Screen;

/** NeoForge's net.neoforged.neoforge.client.gui.IConfigScreenFactory; registering one on Fabric does nothing (see ModContainer). */
@FunctionalInterface
public interface IConfigScreenFactory {
    Screen createScreen(ModContainer container, Screen parent);
}
