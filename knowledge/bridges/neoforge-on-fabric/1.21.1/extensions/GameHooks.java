package ${package}.extensions;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.LevelReader;

/**
 * Small methods NeoForge 21.1 adds to game classes, each the game's own method with NeoForge's argument (a supplied
 * component type or effect, any living entity), added by GameHooksMixins and, for the compile, Loom's interface
 * injection. A supplied food effect is made when the food is built (the game copies it each time it is applied, as
 * NeoForge makes a new one).
 */
public final class GameHooks {

    private GameHooks() {}

    /** FoodProperties.Builder.effect(Supplier, probability). */
    public interface FoodBuilder {
        default FoodProperties.Builder effect(Supplier<MobEffectInstance> effect, float probability) {
            return ((FoodProperties.Builder) (Object) this).effect(effect.get(), probability);
        }
    }

    /**
     * BlockEntity.DataComponentInput.get and getOrDefault with a supplied type. The game's interface is protected without
     * NeoForge's access transformer, so this one declares its two methods (which every DataComponentInput has).
     */
    public interface ComponentInput {
        <T> T get(DataComponentType<T> type);

        <T> T getOrDefault(DataComponentType<? extends T> type, T defaultValue);

        default <T> T get(Supplier<? extends DataComponentType<T>> type) {
            return get(type.get());
        }

        default <T> T getOrDefault(Supplier<? extends DataComponentType<? extends T>> type, T defaultValue) {
            return getOrDefault(type.get(), defaultValue);
        }
    }

    /** DataComponentMap.Builder.set with a supplied type. */
    public interface ComponentMapBuilder {
        default <T> DataComponentMap.Builder set(Supplier<? extends DataComponentType<T>> type, T value) {
            return ((DataComponentMap.Builder) (Object) this).set(type.get(), value);
        }
    }

    /** Rarity.getStyleModifier: the name's style in the rarity's colour (NeoForge's default for the game's rarities). */
    public interface RarityStyle {
        default UnaryOperator<Style> getStyleModifier() {
            return style -> style.withColor(((Rarity) (Object) this).color());
        }
    }

    /** LevelReader.isAreaLoaded: whether every chunk within range of the position is loaded. */
    public interface AreaLoaded {
        default boolean isAreaLoaded(BlockPos center, int range) {
            return ((LevelReader) this).hasChunksAt(center.offset(-range, -range, -range), center.offset(range, range, range));
        }
    }

    /** ItemStack.hurtAndBreak for any living entity (the game's takes a player, or none). */
    public interface StackDamage {
        default void hurtAndBreak(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBroken) {
            ((ItemStack) (Object) this).hurtAndBreak(amount, level, entity instanceof ServerPlayer player ? player : null, onBroken);
        }
    }
}
