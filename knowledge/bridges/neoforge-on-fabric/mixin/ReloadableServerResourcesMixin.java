package ${package}.mixin;

import ${package}.extensions.IReloadableServerResourcesExtension;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's ReloadableServerResources patch: the lookup the reload loads data with (its registries with updated tags). */
@Mixin(ReloadableServerResources.class)
abstract class ReloadableServerResourcesMixin implements IReloadableServerResourcesExtension {

    @Unique
    private HolderLookup.Provider bridge$registryLookup;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void keepLookup(
        ReloadableServerRegistries.LoadResult loadResult,
        FeatureFlagSet enabledFeatures,
        Commands.CommandSelection commandSelection,
        List<?> postponedTags,
        PermissionSet functionCompilationPermissions,
        List<?> newComponents,
        CallbackInfo ci
    ) {
        bridge$registryLookup = loadResult.lookupWithUpdatedTags();
    }

    @Override
    public HolderLookup.Provider getRegistryLookup() {
        return bridge$registryLookup;
    }
}
