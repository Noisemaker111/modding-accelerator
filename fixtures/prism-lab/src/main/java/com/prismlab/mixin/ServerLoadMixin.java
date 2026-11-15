package com.prismlab.mixin;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Proves a mixin still applies to the current Minecraft classes, and can be reloaded. */
@Mixin(MinecraftServer.class)
public class ServerLoadMixin {
	@Inject(at = @At("HEAD"), method = "tickServer")
	private void prismlab$onTick(CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		if (server.getTickCount() % 600 == 0) {
			System.out.println("PRISMLAB-MIXIN active tick=" + server.getTickCount());
		}
	}
}




