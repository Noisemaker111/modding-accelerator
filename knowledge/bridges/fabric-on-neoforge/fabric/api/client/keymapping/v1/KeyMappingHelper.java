package ${package}.fabric.api.client.keymapping.v1;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Fabric API's net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper on NeoForge's RegisterKeyMappingsEvent:
 * a key mapping registered here is added to the game's options in that event, after the converted client entrypoint
 * (which runs at the start of it), so registering from the client entrypoint works as on Fabric. As on Fabric, a key
 * mapping registered once the options exist throws, and so does one registered twice or two with one name.
 */
public final class KeyMappingHelper {

    private static final List<KeyMapping> PENDING = new ArrayList<>();
    private static boolean registered;

    private KeyMappingHelper() {}

    public static synchronized KeyMapping registerKeyMapping(KeyMapping keyMapping) {
        Objects.requireNonNull(keyMapping, "key mapping cannot be null");
        if (registered) throw new IllegalStateException("GameOptions has already been initialised");
        for (KeyMapping existing : PENDING) {
            if (existing == keyMapping) throw new IllegalArgumentException("Attempted to register a key mapping twice: " + keyMapping.getName());
            if (existing.getName().equals(keyMapping.getName())) throw new IllegalArgumentException("Attempted to register two key mappings with equal ID: " + keyMapping.getName() + "!");
        }
        PENDING.add(keyMapping);
        return keyMapping;
    }

    public static InputConstants.Key getBoundKeyOf(KeyMapping keyMapping) {
        return keyMapping.getKey();
    }

    /** Adds every key mapping registered so far to the options (the bridge's ClientHooks calls this, last in the event). */
    public static synchronized void register(RegisterKeyMappingsEvent event) {
        registered = true;
        for (KeyMapping keyMapping : PENDING) event.register(keyMapping);
        PENDING.clear();
    }
}
