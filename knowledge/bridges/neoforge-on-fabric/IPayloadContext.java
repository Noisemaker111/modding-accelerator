package ${package};

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's net.neoforged.neoforge.network.handling.IPayloadContext, as far as a play payload's handler uses it: the
 * player, the game thread, a reply, the direction. Fabric already runs the handler on the game thread, so queued work
 * runs at once.
 */
public interface IPayloadContext {
    Player player();

    void reply(CustomPacketPayload payload);

    void disconnect(Component reason);

    PacketFlow flow();

    default CompletableFuture<Void> enqueueWork(Runnable work) {
        work.run();
        return CompletableFuture.completedFuture(null);
    }

    default <T> CompletableFuture<T> enqueueWork(Supplier<T> work) {
        return CompletableFuture.completedFuture(work.get());
    }
}
