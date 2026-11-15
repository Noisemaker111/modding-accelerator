package net.fabricmc.api;

/** Compile-time stand-in for Fabric's server entrypoint; Fabric and Quilt supply the real interface. */
public interface DedicatedServerModInitializer { void onInitializeServer(); }
