package ${package}.fabric.api.event.lifecycle.v1;

import net.minecraft.core.RegistryAccess;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;

/**
 * Fabric API's net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents. TAGS_LOADED is posted from NeoForge's
 * TagsUpdatedEvent, which fires where Fabric's does: on the server when a data reload has bound the tags
 * (ServerDataLoad, client false), and on the client when the server's tags arrive (ClientPacketReceived, client true),
 * with the registries the tags were bound to.
 */
public final class CommonLifecycleEvents {

    public static final Event<TagsLoaded> TAGS_LOADED = EventFactory.createArrayBacked(TagsLoaded.class, callbacks -> (registries, client) -> {
        for (TagsLoaded callback : callbacks) callback.onTagsLoaded(registries, client);
    });

    static {
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, event -> TAGS_LOADED.invoker().onTagsLoaded(event.getRegistries(), event instanceof TagsUpdatedEvent.ClientPacketReceived));
    }

    private CommonLifecycleEvents() {}

    @FunctionalInterface
    public interface TagsLoaded {
        void onTagsLoaded(RegistryAccess registries, boolean client);
    }
}
