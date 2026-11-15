package ${package}.capabilities;

/** NeoForge's net.neoforged.neoforge.capabilities.ICapabilityProvider: the capability of a block entity, entity or item stack, or null. */
@FunctionalInterface
public interface ICapabilityProvider<O, C, T> {

    T getCapability(O object, C context);
}
