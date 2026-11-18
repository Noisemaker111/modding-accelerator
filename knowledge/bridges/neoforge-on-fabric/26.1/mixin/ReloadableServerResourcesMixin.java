package ${package}.mixin;

import ${package}.extensions.IReloadableServerResourcesExtension;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's ReloadableServerResources patch: the lookup the reload loads data with (on 26.1 the game passes it in). */
@Mixin(ReloadableServerResources.class)
abstract class ReloadableServerResourcesMixin implements IReloadableServerResourcesExtension {

    @Unique
    private HolderLookup.Provider bridge$registryLookup;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void keepLookup(
        LayeredRegistryAccess<RegistryLayer> registryAccess,
        HolderLookup.Provider registryLookup,
        FeatureFlagSet enabledFeatures,
        Commands.CommandSelection commandSelection,
        List<?> postponedTags,
        PermissionSet functionCompilationPermissions,
        List<?> newComponents,
        CallbackInfo ci
    ) {
        bridge$registryLookup = registryLookup;
    }

    @Override
    public HolderLookup.Provider getRegistryLookup() {
        return bridge$registryLookup;
    }
}
