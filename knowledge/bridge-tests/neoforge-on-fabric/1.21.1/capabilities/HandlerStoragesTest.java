package ${package}.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ${package}.fluids.FluidStack;
import ${package}.fluids.capability.IFluidHandler;
import ${package}.items.IItemHandler;
import ${package}.items.ItemStackHandler;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NeoForge 21.1's handlers and Fabric API's storages, each as the other: a handler changed inside a Fabric transaction
 * keeps the change when it commits and loses it when it aborts (a nested one only its own), a Fabric inventory answers
 * a handler's simulated and real calls, fluid moves in whole millibuckets of 81 droplets, and an ItemStackHandler saves
 * and loads its slots in NeoForge's form.
 */
class HandlerStoragesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aHandlerTakesPartInFabricTransactions() {
        ItemStackHandler handler = new ItemStackHandler(2);
        Storage<ItemVariant> storage = ItemStorages.storage(handler);
        ItemVariant stone = ItemVariant.of(Items.STONE);
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(10, storage.insert(stone, 10, transaction));
            assertEquals(10, handler.getStackInSlot(0).getCount());
        }
        assertTrue(handler.getStackInSlot(0).isEmpty());

        try (Transaction outer = Transaction.openOuter()) {
            storage.insert(stone, 5, outer);
            try (Transaction inner = outer.openNested()) {
                assertEquals(3, storage.extract(stone, 3, inner));
            }
            assertEquals(5, handler.getStackInSlot(0).getCount());
            outer.commit();
        }
        assertEquals(5, handler.getStackInSlot(0).getCount());
    }

    @Test
    void aFabricInventoryIsAHandler() {
        SimpleContainer container = new SimpleContainer(1);
        IItemHandler handler = ItemStorages.handler(InventoryStorage.of(container, null));
        assertEquals(1, handler.getSlots());
        assertEquals(36, handler.insertItem(0, new ItemStack(Items.STONE, 100), true).getCount());
        assertTrue(container.getItem(0).isEmpty());
        assertTrue(handler.insertItem(0, new ItemStack(Items.STONE, 20), false).isEmpty());
        assertEquals(20, container.getItem(0).getCount());
        assertEquals(7, handler.extractItem(0, 7, false).getCount());
        assertEquals(13, container.getItem(0).getCount());
    }

    @Test
    void fluidMovesInMillibuckets() {
        Tank tank = new Tank();
        Storage<FluidVariant> storage = FluidStorages.storage(tank);
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(FluidConstants.BUCKET, storage.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET, transaction));
            assertEquals(1000, tank.stack.getAmount());
        }
        assertTrue(tank.stack.isEmpty());

        SingleVariantStorage<FluidVariant> fabric = new SingleVariantStorage<>() {
            @Override
            protected FluidVariant getBlankVariant() {
                return FluidVariant.blank();
            }

            @Override
            protected long getCapacity(FluidVariant variant) {
                return 4 * FluidConstants.BUCKET;
            }
        };
        IFluidHandler handler = FluidStorages.handler(fabric);
        assertEquals(500, handler.fill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.SIMULATE));
        assertEquals(0, fabric.amount);
        assertEquals(500, handler.fill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE));
        assertEquals(500 * 81, fabric.amount);
        assertEquals(200, handler.drain(200, IFluidHandler.FluidAction.EXECUTE).getAmount());
        assertEquals(300, handler.getFluidInTank(0).getAmount());
    }

    @Test
    void itemStackHandlerSavesInNeoForgesForm() {
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        ItemStackHandler handler = new ItemStackHandler(3);
        handler.setStackInSlot(2, new ItemStack(Items.DIAMOND, 4));
        CompoundTag saved = handler.serializeNBT(registries);
        assertEquals(3, saved.getInt("Size"));
        assertEquals(2, saved.getList("Items", 10).getCompound(0).getInt("Slot"));
        ItemStackHandler loaded = new ItemStackHandler();
        loaded.deserializeNBT(registries, saved);
        assertEquals(3, loaded.getSlots());
        assertEquals(4, loaded.getStackInSlot(2).getCount());
        assertTrue(loaded.getStackInSlot(0).isEmpty());
    }

    /** A one-tank handler of up to 8 buckets, changed at once. */
    static final class Tank implements IFluidHandler {
        FluidStack stack = FluidStack.EMPTY;

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return stack;
        }

        @Override
        public int getTankCapacity(int tank) {
            return 8000;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack resource) {
            return true;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (!stack.isEmpty() && !FluidStack.isSameFluidSameComponents(stack, resource)) return 0;
            int filled = Math.min(8000 - stack.getAmount(), resource.getAmount());
            if (action.execute() && filled > 0) stack = resource.copyWithAmount(stack.getAmount() + filled);
            return filled;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.isSameFluidSameComponents(stack, resource) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            int drained = Math.min(maxDrain, stack.getAmount());
            FluidStack out = stack.copyWithAmount(drained);
            if (action.execute()) stack = drained == stack.getAmount() ? FluidStack.EMPTY : stack.copyWithAmount(stack.getAmount() - drained);
            return out;
        }
    }
}
