package ${package}.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;

/**
 * Fabric API's net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback, posted from NeoForge's
 * RegisterClientCommandsEvent, which fires at the same moments as Fabric's (each time the client gets the server's
 * command tree), with a dispatcher of FabricClientCommandSource; see ClientCommands for how its commands run.
 */
@FunctionalInterface
public interface ClientCommandRegistrationCallback {

    Event<ClientCommandRegistrationCallback> EVENT = EventFactory.createArrayBacked(ClientCommandRegistrationCallback.class, callbacks -> (dispatcher, buildContext) -> {
        for (ClientCommandRegistrationCallback callback : callbacks) callback.register(dispatcher, buildContext);
    });

    void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext);
}
