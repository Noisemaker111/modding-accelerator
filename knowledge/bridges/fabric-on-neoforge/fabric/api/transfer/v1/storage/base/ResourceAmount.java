package ${package}.fabric.api.transfer.v1.storage.base;

/** Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.base.ResourceAmount: a resource and an amount of it. */
public record ResourceAmount<T>(T resource, long amount) {}
