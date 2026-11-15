package ${package};

/** NeoForge's net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent, posted when the main entrypoint ends. */
public class RegisterPayloadHandlersEvent extends Event {

    public PayloadRegistrar registrar(String version) {
        return new PayloadRegistrar(version);
    }
}
