package dev.modlab.client;

/**
 * Stub for the Fabric entrypoint in the parent tree, which cannot compile without
 * Fabric's API. NeoForge's client entry is {@code com.prismlab.client.PrismLabClient}.
 */
public class LabEntry {
	public void onInitializeClient() {
		// nothing to install: PrismLabClient starts the lab control socket
	}
}
