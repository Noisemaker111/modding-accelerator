package ${package};

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/** The IPayloadContext of a payload the client received, from Fabric's ClientPlayNetworking.Context (client only). */
final class ClientPayloadContext implements IPayloadContext {

    final ClientPlayNetworking.Context context;

    ClientPayloadContext(ClientPlayNetworking.Context context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void reply(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void disconnect(Component reason) {
        context.client().disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.CLIENTBOUND;
    }
}
