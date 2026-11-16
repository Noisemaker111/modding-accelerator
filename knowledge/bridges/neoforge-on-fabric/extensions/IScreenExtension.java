package ${package}.extensions;

import net.minecraft.client.Minecraft;

/**
 * The getMinecraft() NeoForge adds to the game's Screen: the client, which is the screen's own minecraft field once the
 * screen is opened. Added by ScreenMinecraftMixin, and for the compile by Loom's interface injection.
 */
public interface IScreenExtension {

    default Minecraft getMinecraft() {
        return Minecraft.getInstance();
    }
}
