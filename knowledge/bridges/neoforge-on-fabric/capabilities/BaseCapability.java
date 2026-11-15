package ${package}.capabilities;

import net.minecraft.resources.Identifier;

/** NeoForge's net.neoforged.neoforge.capabilities.BaseCapability: a capability's name, object class and context class. */
public abstract class BaseCapability<T, C> {

    private final Identifier name;
    private final Class<T> typeClass;
    private final Class<C> contextClass;

    protected BaseCapability(Identifier name, Class<T> typeClass, Class<C> contextClass) {
        this.name = name;
        this.typeClass = typeClass;
        this.contextClass = contextClass;
    }

    public final Identifier name() {
        return name;
    }

    public final Class<T> typeClass() {
        return typeClass;
    }

    public final Class<C> contextClass() {
        return contextClass;
    }
}
