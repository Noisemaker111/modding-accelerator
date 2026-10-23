package com.prismlab.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Proves a mixin still applies to the current Minecraft classes. */
@Mixin(MinecraftServer.class)
public class ServerLoadMixin {
	// 1.21.1's tick entry point takes the time-left supplier; 26.3 has no arguments.
	@Inject(at = @At("HEAD"), method = "tickServer")
	private void prismlab$onTick(BooleanSupplier hasTimeLeft, CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		if (server.getTickCount() % 600 == 0) {
			System.out.println("PRISMLAB-MIXIN active tick=" + server.getTickCount());
		}
	}
}
