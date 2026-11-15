package ${package};

import net.minecraft.core.RegistryAccess;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.TagsUpdatedEvent, posted from Fabric's CommonLifecycleEvents.TAGS_LOADED,
 * which fires where NeoForge posts it: SERVER_DATA_LOAD after a data load bound the server's tags
 * (ReloadableServerResources.updateRegistryTags), CLIENT_PACKET_RECEIVED after the client applied the server's tags.
 * The same members as NeoForge's.
 */
public class TagsUpdatedEvent extends Event {

    private final RegistryAccess registryAccess;
    private final UpdateCause updateCause;
    private final boolean integratedServer;

    public TagsUpdatedEvent(RegistryAccess registryAccess, boolean fromClientPacket, boolean isIntegratedServerConnection) {
        this.registryAccess = registryAccess;
        this.updateCause = fromClientPacket ? UpdateCause.CLIENT_PACKET_RECEIVED : UpdateCause.SERVER_DATA_LOAD;
        this.integratedServer = isIntegratedServerConnection;
    }

    public RegistryAccess getRegistryAccess() {
        return registryAccess;
    }

    public UpdateCause getUpdateCause() {
        return updateCause;
    }

    public boolean shouldUpdateStaticData() {
        return updateCause == UpdateCause.SERVER_DATA_LOAD || !integratedServer;
    }

    public enum UpdateCause {
        SERVER_DATA_LOAD,
        CLIENT_PACKET_RECEIVED
    }
}
