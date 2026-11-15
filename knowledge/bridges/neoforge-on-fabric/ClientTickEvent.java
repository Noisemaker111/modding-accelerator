package ${package};

/** NeoForge's net.neoforged.neoforge.client.event.ClientTickEvent, posted from Fabric's ClientTickEvents. */
public abstract class ClientTickEvent extends Event {

    public static class Pre extends ClientTickEvent {}

    public static class Post extends ClientTickEvent {}
}
