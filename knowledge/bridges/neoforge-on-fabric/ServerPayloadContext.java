package ${package};

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/** The IPayloadContext of a payload the server received, from Fabric's ServerPlayNetworking.Context. */
final class ServerPayloadContext implements IPayloadContext {

    final ServerPlayNetworking.Context context;

    ServerPayloadContext(ServerPlayNetworking.Context context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void reply(CustomPacketPayload payload) {
        ServerPlayNetworking.send(context.player(), payload);
    }

    @Override
    public void disconnect(Component reason) {
        context.player().connection.disconnect(reason);
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }
}
