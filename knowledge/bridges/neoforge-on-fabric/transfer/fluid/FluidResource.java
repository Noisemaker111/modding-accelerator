package ${package}.transfer.fluid;

import ${package}.fluids.FluidStack;
import ${package}.fluids.FluidType;
import ${package}.transfer.resource.DataComponentHolderResource;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.transfer.fluid.FluidResource: a fluid with its data components, without an amount.
 * It is the same thing as Fabric API's FluidVariant; TransferBridge turns one into the other. Amounts that go with it
 * are millibuckets (NeoForge's unit), 81 of Fabric's droplets each.
 */
public final class FluidResource implements DataComponentHolderResource<Fluid> {

    public static final FluidResource EMPTY = new FluidResource(FluidStack.EMPTY);

    public static final Codec<FluidResource> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(i -> i.group(
        BuiltInRegistries.FLUID.holderByNameCodec().fieldOf("id").forGetter(FluidResource::typeHolder),
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(FluidResource::getComponentsPatch)
    ).apply(i, FluidResource::of)));

    public static final Codec<FluidResource> OPTIONAL_CODEC = ExtraCodecs.optionalEmptyMap(CODEC).xmap(
        optional -> optional.orElse(EMPTY),
        resource -> resource.isEmpty() ? Optional.empty() : Optional.of(resource)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, FluidResource> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.holderRegistry(Registries.FLUID),
        FluidResource::typeHolder,
        DataComponentPatch.STREAM_CODEC,
        FluidResource::getComponentsPatch,
        FluidResource::of
    );

    /** A stack of one millibucket, never changed. */
    private final FluidStack innerStack;

    private FluidResource(FluidStack stack) {
        this.innerStack = stack;
    }

    public static FluidResource of(FluidStack stack) {
        return stack.isEmpty() ? EMPTY : new FluidResource(stack.copyWithAmount(1));
    }

    public static FluidResource of(Fluid fluid) {
        return of(new FluidStack(fluid, 1));
    }

    public static FluidResource of(Fluid fluid, DataComponentPatch patch) {
        return of(new FluidStack(fluid, 1, patch));
    }

    public static FluidResource of(Holder<Fluid> fluid) {
        return of(new FluidStack(fluid, 1));
    }

    public static FluidResource of(Holder<Fluid> holder, DataComponentPatch patch) {
        return of(new FluidStack(holder, 1, patch));
    }

    @Override
    public Fluid value() {
        return innerStack.getFluid();
    }

    public Fluid getFluid() {
        return innerStack.getFluid();
    }

    @Override
    public Holder<Fluid> typeHolder() {
        return innerStack.typeHolder();
    }

    public FluidType getFluidType() {
        return innerStack.getFluidType();
    }

    @Override
    public boolean isEmpty() {
        return innerStack.isEmpty();
    }

    @Override
    public FluidResource withMergedPatch(DataComponentPatch patch) {
        if (isEmpty() || patch.isEmpty()) return this;
        FluidStack stack = innerStack.copy();
        stack.applyComponents(patch);
        return new FluidResource(stack);
    }

    @Override
    public <D> FluidResource with(DataComponentType<D> type, @Nullable D data) {
        if (isEmpty()) return this;
        FluidStack stack = innerStack.copy();
        stack.set(type, data);
        return new FluidResource(stack);
    }

    @Override
    public <D> FluidResource with(Supplier<? extends DataComponentType<D>> type, @Nullable D data) {
        return with(type.get(), data);
    }

    @Override
    public FluidResource without(DataComponentType<?> type) {
        if (isEmpty()) return this;
        FluidStack stack = innerStack.copy();
        stack.remove(type);
        return new FluidResource(stack);
    }

    @Override
    public FluidResource without(Supplier<? extends DataComponentType<?>> type) {
        return without(type.get());
    }

    @Override
    public DataComponentMap getComponents() {
        return innerStack.getComponents();
    }

    @Override
    public DataComponentPatch getComponentsPatch() {
        return innerStack.getComponentsPatch();
    }

    public FluidStack toStack(int amount) {
        return innerStack.copyWithAmount(amount);
    }

    @Override
    public boolean isComponentsPatchEmpty() {
        return innerStack.isComponentsPatchEmpty();
    }

    public boolean is(FluidType fluidType) {
        return getFluidType().equals(fluidType);
    }

    public boolean matches(FluidStack stack) {
        return FluidStack.isSameFluidSameComponents(stack, innerStack);
    }

    public boolean test(Predicate<FluidStack> predicate) {
        return predicate.test(innerStack);
    }

    public Component getHoverName() {
        return innerStack.getHoverName();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj == this || obj instanceof FluidResource other && FluidStack.isSameFluidSameComponents(innerStack, other.innerStack);
    }

    @Override
    public int hashCode() {
        return FluidStack.hashFluidAndComponents(innerStack);
    }

    @Override
    public String toString() {
        return isEmpty() ? "FluidResource[EMPTY]" : "FluidResource[" + innerStack.getFluid() + " " + innerStack.getComponentsPatch() + "]";
    }
}
