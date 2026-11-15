package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's ItemStack patch: on the server, useOn runs the item's use through CommonHooks.onPlaceItemIntoWorld, which
 * records the blocks it changes and posts BlockEvent.EntityPlaceEvent or EntityMultiPlaceEvent for them; a cancelled
 * event puts the blocks and the item back and the use fails. Here the game's own Item.useOn call is wrapped
 * (CommonHooks.placeItemIntoWorld), so the game's adventure-mode check and stat stay as they are.
 */
@Mixin(ItemStack.class)
abstract class PlaceBlockMixin {

    @WrapOperation(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult neoforgeBridge$place(Item item, UseOnContext context, Operation<InteractionResult> useOn) {
        return CommonHooks.placeItemIntoWorld(context, c -> useOn.call(item, c));
    }
}
