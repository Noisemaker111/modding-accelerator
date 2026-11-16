package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What NeoForge 21.1 adds to game classes behaves as on NeoForge: a supplied food effect is the food's effect, a rarity
 * colours the name in its own colour, and CropGrowEvent.Pre decides whether a crop grows (GROW, DO_NOT_GROW, or the
 * crop's own answer). Runs inside Fabric Loader, so the bridge's mixins have added the methods.
 */
class GameHooksTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void suppliedFoodEffectsAreTheFoodsEffects() {
        FoodProperties food = new FoodProperties.Builder().nutrition(2).effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 100), 0.5F).build();
        assertEquals(1, food.effects().size());
        assertEquals(MobEffects.REGENERATION, food.effects().get(0).effect().getEffect());
        assertEquals(0.5F, food.effects().get(0).probability());
    }

    @Test
    void aRarityColoursTheName() {
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), Rarity.RARE.getStyleModifier().apply(Style.EMPTY).getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE), Rarity.EPIC.getStyleModifier().apply(Style.EMPTY).getColor());
    }

    @Test
    void toolsCanDoTheirAbilities() {
        assertTrue(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE).canPerformAction(ItemAbilities.AXE_STRIP));
        assertFalse(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE).canPerformAction(ItemAbilities.HOE_TILL));
        assertTrue(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HOE).canPerformAction(ItemAbilities.HOE_TILL));
        assertFalse(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK).canPerformAction(ItemAbilities.AXE_DIG));
    }

    @Test
    void aBlockTakesAnotherBlocksLoot() {
        net.minecraft.world.level.block.Block block = new net.minecraft.world.level.block.Block(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().lootFrom(() -> net.minecraft.world.level.block.Blocks.STONE)
        );
        assertEquals(net.minecraft.world.level.block.Blocks.STONE.getLootTable(), block.getLootTable());
    }

    @Test
    void cropGrowthFollowsTheEvent() {
        CropGrowEvent.Pre.Result[] answer = { CropGrowEvent.Pre.Result.DEFAULT };
        Bus.INSTANCE.addListener(CropGrowEvent.Pre.class, event -> event.setResult(answer[0]));
        assertTrue(CommonHooks.canCropGrow(null, null, null, true));
        assertFalse(CommonHooks.canCropGrow(null, null, null, false));
        answer[0] = CropGrowEvent.Pre.Result.GROW;
        assertTrue(CommonHooks.canCropGrow(null, null, null, false));
        answer[0] = CropGrowEvent.Pre.Result.DO_NOT_GROW;
        assertFalse(CommonHooks.canCropGrow(null, null, null, true));
    }
}
