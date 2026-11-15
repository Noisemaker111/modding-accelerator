package ${package}.transfer;

import ${package}.transfer.resource.Resource;

/** NeoForge's net.neoforged.neoforge.transfer.IndexModifier: sets an index's contents outside a transaction (menus, loading). */
@FunctionalInterface
public interface IndexModifier<T extends Resource> {

    void set(int index, T resource, int amount);
}
