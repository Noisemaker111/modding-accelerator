package ${package};

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent, posted for every tab from Fabric's
 * ItemGroupEvents.MODIFY_ENTRIES_ALL (Minecraft 1.21.1); what a listener adds goes into Fabric's entries for that tab.
 */
public final class BuildCreativeModeTabContentsEvent extends Event implements CreativeModeTab.Output {

    final CreativeModeTab tab;
    final FabricItemGroupEntries entries;

    BuildCreativeModeTabContentsEvent(CreativeModeTab tab, FabricItemGroupEntries entries) {
        this.tab = tab;
        this.entries = entries;
    }

    public CreativeModeTab getTab() {
        return tab;
    }

    public ResourceKey<CreativeModeTab> getTabKey() {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow();
    }

    public FeatureFlagSet getFlags() {
        return entries.getEnabledFeatures();
    }

    public CreativeModeTab.ItemDisplayParameters getParameters() {
        return entries.getContext();
    }

    public boolean hasPermissions() {
        return entries.shouldShowOpRestrictedItems();
    }

    @Override
    public void accept(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        entries.accept(stack, visibility);
    }

    public void insertAfter(ItemStack existing, ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        entries.addAfter(existing, List.of(stack), visibility);
    }

    public void insertBefore(ItemStack existing, ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        entries.addBefore(existing, List.of(stack), visibility);
    }

    public void insertFirst(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        entries.prepend(stack, visibility);
    }

    public void remove(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        Predicate<ItemStack> same = other -> ItemStack.isSameItemSameComponents(other, stack);
        if (visibility != CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY) entries.getDisplayStacks().removeIf(same);
        if (visibility != CreativeModeTab.TabVisibility.PARENT_TAB_ONLY) entries.getSearchTabStacks().removeIf(same);
    }
}
