package modaccel.packprobe;

/** Fabric's and Quilt's way in (fabric.mod.json's "server" entrypoint). Kept apart so Forge never loads Fabric's interface. */
public final class ModaccelPackProbeFabric implements net.fabricmc.api.DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() { ModaccelPackProbe.start(); }
}
