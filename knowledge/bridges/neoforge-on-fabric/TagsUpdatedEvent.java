package ${package};

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;

/**
 * NeoForge's net.neoforged.neoforge.event.TagsUpdatedEvent (26.1 and later: ServerDataLoad and ClientPacketReceived),
 * posted from Fabric's CommonLifecycleEvents.TAGS_LOADED, which fires where NeoForge posts it: ServerDataLoad after a
 * data load bound the server's tags (ReloadableServerResources.updateComponentsAndStaticRegistryTags), with the
 * resources that load made (recorded by ReloadListenersMixin); ClientPacketReceived after the client applied the
 * server's tags, at the end of configuration or on an update packet. The same members as NeoForge's.
 */
public sealed class TagsUpdatedEvent extends Event {

    /** The resources of the data load in progress, for ServerDataLoad (set by AddServerReloadListenersEvent.post). */
    static volatile ReloadableServerResources loading;

    private final RegistryAccess registries;

    protected TagsUpdatedEvent(RegistryAccess registries) {
        this.registries = registries;
    }

    public RegistryAccess getRegistries() {
        return registries;
    }

    /** Whether static data (caches of tag contents) should be rebuilt: false on the client of an integrated server. */
    public boolean shouldUpdateStaticData() {
        return true;
    }

    public static final class ServerDataLoad extends TagsUpdatedEvent {

        private final ReloadableServerResources serverResources;

        public ServerDataLoad(ReloadableServerResources serverResources, RegistryAccess registries) {
            super(registries);
            this.serverResources = serverResources;
        }

        public ReloadableServerResources getServerResources() {
            return serverResources;
        }
    }

    public static final class ClientPacketReceived extends TagsUpdatedEvent {

        private final boolean integratedServer;

        public ClientPacketReceived(RegistryAccess registries, boolean isIntegratedServerConnection) {
            super(registries);
            this.integratedServer = isIntegratedServerConnection;
        }

        @Override
        public boolean shouldUpdateStaticData() {
            return !integratedServer;
        }
    }
}
