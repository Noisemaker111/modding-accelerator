package ${package}.mixin;

import ${package}.extensions.BlockEntityHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ClientPacketListener patch: a block entity's update packet goes to its onDataPacket (whose default loads
 * the packet's data, as the game does) instead of straight to loadWithComponents. The handler is the lambda
 * handleBlockEntityData runs for the block entity (its intermediary name: Mojang's names leave lambdas unnamed).
 */
@Mixin(ClientPacketListener.class)
abstract class BlockEntityDataMixin {

    @WrapOperation(method = "method_38542", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void neoforgeBridge$onDataPacket(BlockEntity blockEntity, CompoundTag tag, HolderLookup.Provider registries, Operation<Void> load, ClientboundBlockEntityDataPacket packet) {
        ((BlockEntityHooks) blockEntity).onDataPacket(((ClientPacketListener) (Object) this).getConnection(), packet, registries);
    }
}
