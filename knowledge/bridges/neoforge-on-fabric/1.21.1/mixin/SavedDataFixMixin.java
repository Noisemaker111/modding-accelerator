package ${package}.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's DimensionDataStorage patch: saved data whose SavedData.Factory has no DataFixTypes (NeoForge's
 * two-argument Factory constructor, which mmt convert writes as the three-argument one with null) is read back
 * as it was written, with no data fixing, where the game would fail on the null.
 */
@Mixin(DimensionDataStorage.class)
abstract class SavedDataFixMixin {

    @WrapOperation(method = "readTagFromDisk", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/datafix/DataFixTypes;update(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;II)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag neoforgeBridge$noDataFix(DataFixTypes type, DataFixer fixer, CompoundTag tag, int from, int to, Operation<CompoundTag> update) {
        if (type == null) return tag;
        return update.call(type, fixer, tag, from, to);
    }
}
