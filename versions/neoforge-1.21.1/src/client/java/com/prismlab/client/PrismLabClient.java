package com.prismlab.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import com.prismlab.PrismLab;

import dev.modlab.client.LabControl;

/**
 * NeoForge 1.21.1 client entry. Fabric's {@code ClientModInitializer} and
 * {@code ClientTickEvents.END_CLIENT_TICK} become a client-only {@code @Mod} plus
 * {@code ClientTickEvent.Post} on the game bus.
 */
@Mod(value = PrismLab.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = PrismLab.MOD_ID, value = Dist.CLIENT)
public final class PrismLabClient {
	public PrismLabClient() {
		LabControl.startIfRequested();
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		LabControl.clientTick();
	}
}
