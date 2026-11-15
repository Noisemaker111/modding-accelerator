package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The client-only half of the bridge's common events (loaded only on a client, so a dedicated server never loads the
 * client classes it names): EntityLeaveLevelEvent from Fabric's ClientEntityEvents.ENTITY_UNLOAD, the receiver of
 * IEntityWithComplexSpawn's spawn data, the client's start for the load-complete lifecycle events, and whether the
 * client plays on its own integrated server (TagsUpdatedEvent).
 */
final class ClientAccess {

    private ClientAccess() {}

    static void hook() {
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Bus.INSTANCE.post(new EntityLeaveLevelEvent(entity, level)));
        ClientPlayNetworking.registerGlobalReceiver(AdvancedAddEntityPayload.TYPE, (payload, context) -> AdvancedAddEntityPayload.handle(payload, context.player()));
    }

    static void onStarted(Runnable work) {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> work.run());
    }

    static boolean isIntegratedServer() {
        return Minecraft.getInstance().isLocalServer();
    }
}
