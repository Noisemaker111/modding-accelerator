package ${package};

import ${package}.fluids.FluidStack;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions: how a fluid looks (its
 * still, flowing and overlay textures and its tint), with NeoForge's defaults. of(fluid) answers from Fabric API's
 * FluidRenderHandlerRegistry, which holds every fluid's render handler (the game's water and lava, and each mod's), so a
 * converted mod reads the same textures and tint the game draws; with no level to ask, water is the game's default
 * water colour, as NeoForge's water says.
 */
public interface IClientFluidTypeExtensions {

    IClientFluidTypeExtensions DEFAULT = new IClientFluidTypeExtensions() {};

    static IClientFluidTypeExtensions of(FluidState state) {
        return of(state.getType());
    }

    static IClientFluidTypeExtensions of(Fluid fluid) {
        return new IClientFluidTypeExtensions() {
            private TextureAtlasSprite sprite(int index) {
                FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
                if (handler == null) return null;
                TextureAtlasSprite[] sprites = handler.getFluidSprites(null, null, fluid.defaultFluidState());
                return sprites == null || sprites.length <= index ? null : sprites[index];
            }

            @Override
            public int getTintColor() {
                if (fluid.isSame(Fluids.WATER)) return 0xFF3F76E4;
                FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
                if (handler == null) return 0xFFFFFFFF;
                int color = handler.getFluidColor(null, null, fluid.defaultFluidState());
                return (color >>> 24) == 0 ? color | 0xFF000000 : color;
            }

            @Override
            public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
                FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
                if (handler == null || getter == null || pos == null) return getTintColor();
                int color = handler.getFluidColor(getter, pos, state);
                return (color >>> 24) == 0 ? color | 0xFF000000 : color;
            }

            @Override
            public ResourceLocation getStillTexture() {
                TextureAtlasSprite sprite = sprite(0);
                return sprite == null ? null : sprite.contents().name();
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                TextureAtlasSprite sprite = sprite(1);
                return sprite == null ? null : sprite.contents().name();
            }

            @Override
            public ResourceLocation getOverlayTexture() {
                TextureAtlasSprite sprite = sprite(2);
                return sprite == null ? null : sprite.contents().name();
            }
        };
    }

    default int getTintColor() {
        return 0xFFFFFFFF;
    }

    default ResourceLocation getStillTexture() {
        return null;
    }

    default ResourceLocation getFlowingTexture() {
        return null;
    }

    default ResourceLocation getOverlayTexture() {
        return null;
    }

    default ResourceLocation getStillTexture(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
        return getStillTexture();
    }

    default ResourceLocation getFlowingTexture(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
        return getFlowingTexture();
    }

    default ResourceLocation getOverlayTexture(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
        return getOverlayTexture();
    }

    default int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
        return getTintColor();
    }

    default int getTintColor(FluidStack stack) {
        return getTintColor();
    }

    default ResourceLocation getStillTexture(FluidStack stack) {
        return getStillTexture();
    }

    default ResourceLocation getFlowingTexture(FluidStack stack) {
        return getFlowingTexture();
    }

    default ResourceLocation getOverlayTexture(FluidStack stack) {
        return getOverlayTexture();
    }
}
