package ${package};

import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.extensions.common.IClientItemExtensions, with NeoForge's defaults: how an
 * item draws. Registered with RegisterClientExtensionsEvent.registerItem; its custom renderer draws the item through
 * Fabric API's BuiltinItemRendererRegistry (for an item whose model is builtin/entity, as on NeoForge). Fabric has no
 * hook for the other methods (arm pose, hand transform, bobbing), so they are kept but not asked.
 */
public interface IClientItemExtensions {

    IClientItemExtensions DEFAULT = new IClientItemExtensions() {};

    static IClientItemExtensions of(ItemStack stack) {
        return RegisterClientExtensionsEvent.ITEMS.getOrDefault(stack.getItem(), DEFAULT);
    }

    static IClientItemExtensions of(net.minecraft.world.item.Item item) {
        return RegisterClientExtensionsEvent.ITEMS.getOrDefault(item, DEFAULT);
    }

    default @Nullable Font getFont(ItemStack stack, FontContext context) {
        return null;
    }

    default HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entityLiving, InteractionHand hand, ItemStack itemStack) {
        return null;
    }

    /** The item's own renderer; none by default (the game draws the item from its model). */
    default @Nullable BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return null;
    }

    default boolean shouldBobAsEntity(ItemStack stack) {
        return true;
    }

    default boolean shouldSpreadAsEntity(ItemStack stack) {
        return true;
    }

    enum FontContext {
        ITEM_COUNT,
        TOOLTIP
    }
}
