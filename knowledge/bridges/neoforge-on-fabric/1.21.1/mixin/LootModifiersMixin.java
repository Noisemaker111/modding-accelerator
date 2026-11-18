package ${package}.mixin;

import ${package}.loot.LootModifierManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's LootTable patch: the items a loot table rolls (getRandomItems with a context, which every other
 * getRandomItems ends in) go through the global loot modifiers, with the table known while it rolls.
 */
@Mixin(LootTable.class)
abstract class LootModifiersMixin {

    @Inject(method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("HEAD"))
    private void neoforgeBridge$rollStarts(LootContext context, CallbackInfoReturnable<ObjectArrayList<ItemStack>> info) {
        LootModifierManager.rollStarts((LootTable) (Object) this);
    }

    @Inject(method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("RETURN"), cancellable = true)
    private void neoforgeBridge$modifyLoot(LootContext context, CallbackInfoReturnable<ObjectArrayList<ItemStack>> info) {
        try {
            info.setReturnValue(LootModifierManager.modify(info.getReturnValue(), context));
        } finally {
            LootModifierManager.rollEnds();
        }
    }
}
