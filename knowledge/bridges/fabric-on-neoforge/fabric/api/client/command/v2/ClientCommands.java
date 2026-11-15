package ${package}.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import ${package}.ClientCommandForwarding;

/**
 * Fabric API's net.fabricmc.fabric.api.client.command.v2.ClientCommands. The mod's client commands live in their own
 * dispatcher of FabricClientCommandSource (getActiveDispatcher), filled by ClientCommandRegistrationCallback each time
 * NeoForge posts RegisterClientCommandsEvent; each of its root commands is forwarded from NeoForge's client command
 * dispatcher (see the bridge's ClientCommandForwarding). refreshCommandCompletions asks the server for its command tree
 * again, which makes NeoForge rebuild the client commands, as Fabric's does.
 */
public final class ClientCommands {

    private ClientCommands() {}

    public static CommandDispatcher<FabricClientCommandSource> getActiveDispatcher() {
        return ClientCommandForwarding.active();
    }

    public static void refreshCommandCompletions() {
        if (Minecraft.getInstance().getConnection() == null) throw new IllegalStateException("Not connected to a server (dedicated or integrated)!");
        ClientCommandForwarding.refresh();
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
