package ${package};

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterColorHandlersEvent, posted on the mod bus while the client mod
 * loads (ClientGameEvents.registration). Block tint sources go to Fabric API's BlockColorRegistry and colour resolvers to
 * its ColorResolverRegistry, which the game reads as NeoForge's event fills them. getBlockColors is not mirrored (the
 * game's BlockColors does not exist yet when Fabric loads client mods), nor ItemTintSources (a mod using it is listed).
 */
public abstract class RegisterColorHandlersEvent extends Event implements IModBusEvent {

    protected RegisterColorHandlersEvent() {}

    public static class BlockTintSources extends RegisterColorHandlersEvent {

        public void register(List<BlockTintSource> tintSources, Block... blocks) {
            BlockColorRegistry.register(tintSources, blocks);
        }
    }

    public static class ColorResolvers extends RegisterColorHandlersEvent {

        public void register(ColorResolver resolver) {
            ColorResolverRegistry.register(resolver);
        }
    }
}
