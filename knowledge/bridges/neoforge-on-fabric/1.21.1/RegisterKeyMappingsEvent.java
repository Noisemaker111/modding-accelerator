package ${package};

import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent, posted when the client entrypoint ends
 * (before the game reads its options, as NeoForge posts it); each mapping is registered with Fabric API's key mapping
 * helper.
 */
public class RegisterKeyMappingsEvent extends Event {

    private final Consumer<KeyMapping> register;

    RegisterKeyMappingsEvent(Consumer<KeyMapping> register) {
        this.register = register;
    }

    public void register(KeyMapping key) {
        register.accept(key);
    }
}
