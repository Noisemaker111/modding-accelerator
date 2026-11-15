package ${package}.mixin;

import ${package}.AdvancedAddEntityPayload;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's ServerEntity patch: sendPairingData adds an IEntityWithComplexSpawn's extra spawn data
 * (AdvancedAddEntityPayload) to the spawn bundle right after the game's add-entity packet.
 */
@Mixin(ServerEntity.class)
abstract class ComplexSpawnMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendPairingData", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void neoforgeBridge$spawnData(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> broadcast, CallbackInfo ci) {
        AdvancedAddEntityPayload.send(this.entity, player, broadcast);
    }
}
