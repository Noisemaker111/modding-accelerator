package ${package};

import net.minecraft.world.entity.Entity;

/**
 * NeoForge's net.neoforged.neoforge.entity.PartEntity: a part of a bigger entity (its parent), which NeoForge's hit tests
 * reach through the parent's getParts. On Fabric the game's ender dragon parts are the game's own EnderDragonPart, not
 * this class, and nothing asks an entity for its parts: a mod's own parts exist, but only as the mod uses them.
 */
public abstract class PartEntity<T extends Entity> extends Entity {

    private final T parent;

    public PartEntity(T parent) {
        super(parent.getType(), parent.level());
        this.parent = parent;
    }

    public T getParent() {
        return parent;
    }
}
