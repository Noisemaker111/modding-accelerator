package ${package}.fabric.api.client.command.v2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric API's net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource. On Fabric it is the client's own
 * suggestion provider; here ClientCommands gives each command a source that answers every SharedSuggestionProvider
 * question from that same provider (the connection's) and sends feedback to the chat as Fabric does.
 */
public interface FabricClientCommandSource extends SharedSuggestionProvider {

    void sendFeedback(Component message);

    void sendError(Component message);

    Minecraft getClient();

    LocalPlayer getPlayer();

    default Entity getEntity() {
        return getPlayer();
    }

    default Vec3 getPosition() {
        return getPlayer().position();
    }

    default Vec2 getRotation() {
        return getPlayer().getRotationVector();
    }

    ClientLevel getLevel();

    default Object getMeta(String key) {
        return null;
    }

    boolean attended();
}
