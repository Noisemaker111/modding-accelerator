package ${package}.mixin;

import ${package}.datamaps.builtin.NeoForgeDataMaps;
import net.minecraft.world.level.block.entity.FuelValues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoForge's FURNACE_FUELS data map, into the game's fuel values as it builds them (NeoForgeDataMaps.addFuels). */
@Mixin(FuelValues.Builder.class)
public abstract class FuelValuesBuilderMixin {

    @Inject(method = "build", at = @At("HEAD"))
    private void neoforgeDataMapFuels(CallbackInfoReturnable<FuelValues> info) {
        NeoForgeDataMaps.addFuels((FuelValues.Builder) (Object) this);
    }
}
