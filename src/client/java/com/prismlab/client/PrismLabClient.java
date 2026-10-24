package com.prismlab.client;

import net.fabricmc.api.ClientModInitializer;

import dev.modlab.client.LabControl;

public class PrismLabClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LabControl.startIfRequested();
	}
}


