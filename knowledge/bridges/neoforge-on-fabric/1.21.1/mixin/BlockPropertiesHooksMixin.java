package ${package}.mixin;

import ${package}.extensions.BlockPropertiesHooks;
import ${package}.extensions.LootFromAccess;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives BlockBehaviour.Properties NeoForge 21.1's lootFrom, keeping the supplied block. */
@Mixin(BlockBehaviour.Properties.class)
public abstract class BlockPropertiesHooksMixin implements BlockPropertiesHooks, LootFromAccess {

    @Unique
    private Supplier<? extends Block> neoforgeBridge$lootFrom;

    @Override
    public Supplier<? extends Block> neoforgeBridge$lootFrom() {
        return this.neoforgeBridge$lootFrom;
    }

    @Override
    public void neoforgeBridge$lootFrom(Supplier<? extends Block> block) {
        this.neoforgeBridge$lootFrom = block;
    }
}
