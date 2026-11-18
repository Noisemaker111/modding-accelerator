package ${package}.mixin;

import ${package}.extensions.IScreenExtension;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Screen NeoForge's getMinecraft() (the interface's default is the method). */
@Mixin(Screen.class)
public abstract class ScreenMinecraftMixin implements IScreenExtension {}
