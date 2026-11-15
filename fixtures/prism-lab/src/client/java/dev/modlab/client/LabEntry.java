package dev.modlab.client;

import net.fabricmc.api.ClientModInitializer;

/** Installed entrypoint: starts the lab control socket when the warm client asks for it. */
public class LabEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LabControl.startIfRequested();
	}
}
