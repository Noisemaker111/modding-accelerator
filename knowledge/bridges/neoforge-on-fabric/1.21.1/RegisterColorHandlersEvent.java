package ${package};

import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RegisterColorHandlersEvent, posted on the mod bus while the client
 * mod loads (ClientGameEvents.registration): block and item colors go to Fabric API's ColorProviderRegistry, color
 * resolvers to its ColorResolverRegistry, which the game reads when it builds its color tables, as NeoForge's events
 * fill them.
 */
public abstract class RegisterColorHandlersEvent extends Event implements IModBusEvent {

    protected RegisterColorHandlersEvent() {}

    public static class Block extends RegisterColorHandlersEvent {

        public void register(BlockColor blockColor, net.minecraft.world.level.block.Block... blocks) {
            ColorProviderRegistry.BLOCK.register(blockColor, blocks);
        }

        /** The game's block colors, once the client has made them (null while the client starts). */
        public BlockColors getBlockColors() {
            return Minecraft.getInstance() == null ? null : Minecraft.getInstance().getBlockColors();
        }
    }

    public static class Item extends RegisterColorHandlersEvent {

        public void register(ItemColor itemColor, ItemLike... items) {
            ColorProviderRegistry.ITEM.register(itemColor, items);
        }

        /** Item colors a mod registers into: each registration goes to Fabric API's ColorProviderRegistry, as register does. */
        public ItemColors getItemColors() {
            return new ItemColors() {
                @Override
                public void register(ItemColor itemColor, ItemLike... items) {
                    ColorProviderRegistry.ITEM.register(itemColor, items);
                }
            };
        }

        public BlockColors getBlockColors() {
            return Minecraft.getInstance() == null ? null : Minecraft.getInstance().getBlockColors();
        }
    }

    public static class ColorResolvers extends RegisterColorHandlersEvent {

        public void register(ColorResolver resolver) {
            ColorResolverRegistry.register(resolver);
        }
    }
}
