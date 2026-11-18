package ${package}.fluids;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.fluids.FluidStack on Fabric: a fluid, an amount in millibuckets (FluidType.BUCKET_VOLUME,
 * 1000, to the bucket) and data components. Its name and attributes come from Fabric API's FluidVariantAttributes, so a
 * fluid reads the same as Fabric mods show it. Fabric counts droplets (81000 to the bucket); TransferBridge converts at 81
 * droplets per millibucket where a stack's fluid crosses into a Fabric Storage.
 */
public final class FluidStack implements DataComponentHolder {

    private static final Codec<Holder<Fluid>> FLUID = BuiltInRegistries.FLUID.holderByNameCodec().validate(
        fluid -> fluid.value() == Fluids.EMPTY ? DataResult.error(() -> "Fluid must not be minecraft:empty") : DataResult.success(fluid)
    );

    public static final Codec<FluidStack> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(i -> i.group(
        FLUID.fieldOf("id").forGetter(FluidStack::typeHolder),
        ExtraCodecs.POSITIVE_INT.fieldOf("amount").forGetter(FluidStack::getAmount),
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(FluidStack::getComponentsPatch)
    ).apply(i, FluidStack::new)));

    public static final Codec<FluidStack> OPTIONAL_CODEC = ExtraCodecs.optionalEmptyMap(CODEC).xmap(
        optional -> optional.orElse(FluidStack.EMPTY),
        stack -> stack.isEmpty() ? Optional.empty() : Optional.of(stack)
    );

    /** An amount (0 for the empty stack), then the fluid and its component patch. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FluidStack> OPTIONAL_STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FluidStack decode(RegistryFriendlyByteBuf buffer) {
            int amount = buffer.readVarInt();
            if (amount <= 0) return EMPTY;
            Holder<Fluid> fluid = ByteBufCodecs.holderRegistry(Registries.FLUID).decode(buffer);
            DataComponentPatch patch = DataComponentPatch.STREAM_CODEC.decode(buffer);
            return new FluidStack(fluid, amount, patch);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, FluidStack stack) {
            if (stack.isEmpty()) {
                buffer.writeVarInt(0);
                return;
            }
            buffer.writeVarInt(stack.getAmount());
            ByteBufCodecs.holderRegistry(Registries.FLUID).encode(buffer, stack.typeHolder());
            DataComponentPatch.STREAM_CODEC.encode(buffer, stack.getComponentsPatch());
        }
    };

    /** As OPTIONAL_STREAM_CODEC, for stacks that are never empty. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FluidStack> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FluidStack decode(RegistryFriendlyByteBuf buffer) {
            FluidStack stack = OPTIONAL_STREAM_CODEC.decode(buffer);
            if (stack.isEmpty()) throw new io.netty.handler.codec.DecoderException("Empty FluidStack not allowed");
            return stack;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, FluidStack stack) {
            if (stack.isEmpty()) throw new io.netty.handler.codec.EncoderException("Empty FluidStack not allowed");
            OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        }
    };

    public static final FluidStack EMPTY = new FluidStack(Fluids.EMPTY.builtInRegistryHolder(), 0, new PatchedDataComponentMap(DataComponentMap.EMPTY));

    private final Holder<Fluid> fluid;
    private int amount;
    private final PatchedDataComponentMap components;

    public FluidStack(Fluid fluid, int amount) {
        this(fluid.builtInRegistryHolder(), amount, DataComponentPatch.EMPTY);
    }

    public FluidStack(Fluid fluid, int amount, DataComponentPatch patch) {
        this(fluid.builtInRegistryHolder(), amount, patch);
    }

    public FluidStack(Holder<Fluid> fluid, int amount) {
        this(fluid, amount, DataComponentPatch.EMPTY);
    }

    public FluidStack(Holder<Fluid> fluid, int amount, DataComponentPatch patch) {
        this(fluid, amount, PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY, patch));
    }

    private FluidStack(Holder<Fluid> fluid, int amount, PatchedDataComponentMap components) {
        this.fluid = fluid;
        this.amount = amount;
        this.components = components;
    }

    public boolean isEmpty() {
        return this == EMPTY || fluid.value() == Fluids.EMPTY || amount <= 0;
    }

    public FluidStack split(int amount) {
        int taken = Math.min(amount, this.amount);
        FluidStack out = copyWithAmount(taken);
        shrink(taken);
        return out;
    }

    public FluidStack copyAndClear() {
        if (isEmpty()) return EMPTY;
        FluidStack out = copy();
        setAmount(0);
        return out;
    }

    public Fluid getFluid() {
        return isEmpty() ? Fluids.EMPTY : fluid.value();
    }

    public Holder<Fluid> getFluidHolder() {
        return typeHolder();
    }

    public Holder<Fluid> typeHolder() {
        return isEmpty() ? Fluids.EMPTY.builtInRegistryHolder() : fluid;
    }

    public FluidType getFluidType() {
        return FluidType.of(getFluid());
    }

    public boolean is(FluidType fluidType) {
        return getFluidType().equals(fluidType);
    }

    public FluidStack copy() {
        if (isEmpty()) return EMPTY;
        return new FluidStack(fluid, amount, components.copy());
    }

    public FluidStack copyWithAmount(int amount) {
        if (isEmpty() || amount <= 0) return EMPTY;
        return new FluidStack(fluid, amount, components.copy());
    }

    public static boolean matches(FluidStack first, FluidStack second) {
        return first == second || first.getAmount() == second.getAmount() && isSameFluidSameComponents(first, second);
    }

    public static boolean isSameFluid(FluidStack first, FluidStack second) {
        return first.getFluid() == second.getFluid();
    }

    public static boolean isSameFluidSameComponents(FluidStack first, FluidStack second) {
        if (!isSameFluid(first, second)) return false;
        return first.isEmpty() && second.isEmpty() || first.components.equals(second.components);
    }

    public static int hashFluidAndComponents(@Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return 31 * stack.getFluid().hashCode() + stack.components.hashCode();
    }

    /** The fluid's translation key, as Fabric API names it (FluidVariantAttributes.getName). */
    public String getDescriptionId() {
        return getFluidType().getDescriptionId(this);
    }

    public Component getHoverName() {
        return FluidVariantAttributes.getName(FluidVariant.of(getFluid(), getComponentsPatch()));
    }

    @Override
    public DataComponentMap getComponents() {
        return components;
    }

    public DataComponentPatch getComponentsPatch() {
        return isEmpty() ? DataComponentPatch.EMPTY : components.asPatch();
    }

    public boolean isComponentsPatchEmpty() {
        return getComponentsPatch().isEmpty();
    }

    public <T> @Nullable T set(DataComponentType<T> type, @Nullable T component) {
        return components.set(type, component);
    }

    public <T> @Nullable T remove(DataComponentType<? extends T> type) {
        return components.remove(type);
    }

    public void applyComponents(DataComponentPatch patch) {
        components.applyPatch(patch);
    }

    public void applyComponents(DataComponentMap map) {
        components.setAll(map);
    }

    public int amount() {
        return getAmount();
    }

    public int getAmount() {
        return isEmpty() ? 0 : amount;
    }

    public void setAmount(int amount) {
        if (this == EMPTY) throw new IllegalStateException("Can't modify the empty stack.");
        this.amount = amount;
    }

    public void limitSize(int amount) {
        if (!isEmpty() && getAmount() > amount) setAmount(amount);
    }

    public void grow(int addedAmount) {
        setAmount(this.amount + addedAmount);
    }

    public void shrink(int removedAmount) {
        grow(-removedAmount);
    }

    /**
     * The fluid's tooltip as NeoForge builds it: its name, then (advanced) its registry id and component count, unless
     * the components hide the tooltip outside creative. Fluids add no lines of their own on Fabric (NeoForge's
     * Fluid.appendHoverText and fluid tooltip event have no Fabric counterpart).
     */
    public java.util.List<Component> getTooltipLines(net.minecraft.world.item.Item.TooltipContext context, net.minecraft.world.entity.player.@Nullable Player player, net.minecraft.world.item.TooltipFlag flag) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(getHoverName());
        if (flag.isAdvanced()) lines.add(Component.literal(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(getFluid()).toString()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    @Override
    public String toString() {
        return getAmount() + " " + getFluid();
    }
}
