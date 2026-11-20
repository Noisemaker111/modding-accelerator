package ${package}.items;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * NeoForge 21.1's net.neoforged.neoforge.items.ComponentItemHandler: the slots of an item's ItemContainerContents
 * component, each change written back as a new component value. The holder is the item stack itself (NeoForge's
 * MutableDataComponentHolder is ItemStack's NeoForge interface; the game's own ItemStack has the same set and get).
 */
public class ComponentItemHandler implements IItemHandlerModifiable {

    protected final ItemStack parent;
    protected final DataComponentType<ItemContainerContents> component;
    protected final int size;

    public ComponentItemHandler(ItemStack parent, DataComponentType<ItemContainerContents> component, int size) {
        if (size > 256) throw new IllegalArgumentException("The max size of ItemContainerContents is 256 slots.");
        this.parent = parent;
        this.component = component;
        this.size = size;
    }

    @Override
    public int getSlots() {
        return this.size;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.getStackFromContents(this.getContents(), slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        this.validateSlotIndex(slot);
        if (!this.isItemValid(slot, stack)) throw new RuntimeException("Invalid stack " + stack + " for slot " + slot + ")");
        ItemContainerContents contents = this.getContents();
        ItemStack existing = this.getStackFromContents(contents, slot);
        if (!ItemStack.matches(stack, existing)) this.updateContents(contents, stack, slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack toInsert, boolean simulate) {
        this.validateSlotIndex(slot);
        if (toInsert.isEmpty()) return ItemStack.EMPTY;
        if (!this.isItemValid(slot, toInsert)) return toInsert;
        ItemContainerContents contents = this.getContents();
        ItemStack existing = this.getStackFromContents(contents, slot);
        int insertLimit = Math.min(this.getSlotLimit(slot), toInsert.getMaxStackSize());
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(toInsert, existing)) return toInsert;
            insertLimit -= existing.getCount();
        }
        if (insertLimit <= 0) return toInsert;
        int inserted = Math.min(insertLimit, toInsert.getCount());
        if (!simulate) this.updateContents(contents, toInsert.copyWithCount(existing.getCount() + inserted), slot);
        return toInsert.copyWithCount(toInsert.getCount() - inserted);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        this.validateSlotIndex(slot);
        if (amount == 0) return ItemStack.EMPTY;
        ItemContainerContents contents = this.getContents();
        ItemStack existing = this.getStackFromContents(contents, slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int toExtract = Math.min(amount, existing.getMaxStackSize());
        if (!simulate) this.updateContents(contents, existing.copyWithCount(existing.getCount() - toExtract), slot);
        return existing.copyWithCount(toExtract);
    }

    @Override
    public int getSlotLimit(int slot) {
        return Item.ABSOLUTE_MAX_STACK_SIZE;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return stack.getItem().canFitInsideContainerItems();
    }

    protected void onContentsChanged(int slot, ItemStack oldStack, ItemStack newStack) {}

    protected ItemContainerContents getContents() {
        return this.parent.getOrDefault(this.component, ItemContainerContents.EMPTY);
    }

    protected ItemStack getStackFromContents(ItemContainerContents contents, int slot) {
        this.validateSlotIndex(slot);
        NonNullList<ItemStack> items = NonNullList.withSize(this.size, ItemStack.EMPTY);
        contents.copyInto(items);
        return items.get(slot);
    }

    protected void updateContents(ItemContainerContents contents, ItemStack stack, int slot) {
        this.validateSlotIndex(slot);
        NonNullList<ItemStack> list = NonNullList.withSize(Math.max((int) contents.stream().count(), this.getSlots()), ItemStack.EMPTY);
        contents.copyInto(list);
        ItemStack oldStack = list.get(slot);
        list.set(slot, stack);
        this.parent.set(this.component, ItemContainerContents.fromItems(list));
        this.onContentsChanged(slot, oldStack, stack);
    }

    protected final void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= getSlots()) throw new RuntimeException("Slot " + slot + " not in valid range - [0," + getSlots() + ")");
    }
}
