package mmt.packprobe;

/** Fabric's and Quilt's way in (fabric.mod.json's "server" entrypoint). Kept apart so Forge never loads Fabric's interface. */
public final class MmtPackProbeFabric implements net.fabricmc.api.DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() { MmtPackProbe.start(); }
}
