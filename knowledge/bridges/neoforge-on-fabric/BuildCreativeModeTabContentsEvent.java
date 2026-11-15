package ${package};

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent, posted for every tab from Fabric's
 * CreativeModeTabEvents.MODIFY_OUTPUT_ALL; what a listener adds goes into Fabric's output for that tab.
 */
public final class BuildCreativeModeTabContentsEvent extends Event implements CreativeModeTab.Output {

    final CreativeModeTab tab;
    final FabricCreativeModeTabOutput output;

    BuildCreativeModeTabContentsEvent(CreativeModeTab tab, FabricCreativeModeTabOutput output) {
        this.tab = tab;
        this.output = output;
    }

    public CreativeModeTab getTab() {
        return tab;
    }

    public ResourceKey<CreativeModeTab> getTabKey() {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow();
    }

    public FeatureFlagSet getFlags() {
        return output.getEnabledFeatures();
    }

    public CreativeModeTab.ItemDisplayParameters getParameters() {
        return output.getContext();
    }

    public boolean hasPermissions() {
        return output.shouldShowOpRestrictedItems();
    }

    @Override
    public void accept(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        output.accept(stack, visibility);
    }

    public void insertAfter(ItemStack existing, ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        output.insertAfter(existing, List.of(stack), visibility);
    }

    public void insertBefore(ItemStack existing, ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        output.insertBefore(existing, List.of(stack), visibility);
    }

    public void insertFirst(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        output.prepend(stack, visibility);
    }

    public void remove(ItemStack stack, CreativeModeTab.TabVisibility visibility) {
        removeIf(other -> ItemStack.isSameItemSameComponents(other, stack), visibility);
    }

    public void removeIf(Predicate<? super ItemStack> filter, CreativeModeTab.TabVisibility visibility) {
        if (visibility != CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY) output.getDisplayStacks().removeIf(filter);
        if (visibility != CreativeModeTab.TabVisibility.PARENT_TAB_ONLY) output.getSearchTabStacks().removeIf(filter);
    }
}
