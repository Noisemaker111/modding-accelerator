package ${package};

/**
 * A class as convert writes a Fabric main entrypoint that is also another key's entrypoint: its one public constructor
 * takes the mod's bus (NeoForge's @Mod constructor), and the no-argument one Fabric makes the other entrypoint with is
 * package-private.
 */
public class DualRoleEntrypoint {

    public DualRoleEntrypoint(Object modBus) {
    }

    DualRoleEntrypoint() {
    }
}
