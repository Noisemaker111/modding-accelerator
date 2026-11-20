package ${package}.mixin;

import ${package}.extensions.LootFromAccess;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's BlockBehaviour patch for lootFrom: a block made from properties that take another block's loot
 * answers getLootTable with that block's loot table, asked the first time.
 */
@Mixin(BlockBehaviour.class)
abstract class BlockLootFromMixin {

    @Unique
    private Supplier<? extends Block> neoforgeBridge$lootFrom;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void neoforgeBridge$keepLootFrom(BlockBehaviour.Properties properties, CallbackInfo info) {
        this.neoforgeBridge$lootFrom = ((LootFromAccess) properties).neoforgeBridge$lootFrom();
    }

    @Inject(method = "getLootTable", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$lootTable(CallbackInfoReturnable<ResourceKey<LootTable>> info) {
        if (this.neoforgeBridge$lootFrom != null) info.setReturnValue(this.neoforgeBridge$lootFrom.get().getLootTable());
    }
}
