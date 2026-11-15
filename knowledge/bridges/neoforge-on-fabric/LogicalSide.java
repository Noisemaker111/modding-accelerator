package ${package};

/** NeoForge's net.neoforged.fml.LogicalSide: the client or the server half of the game, as an event reports it. */
public enum LogicalSide {
    CLIENT,
    SERVER;

    public boolean isServer() {
        return !isClient();
    }

    public boolean isClient() {
        return this == CLIENT;
    }
}
