package ${package}.extensions;

import ${package}.PartEntity;

/**
 * NeoForge's multipart entity methods on Entity (IEntityExtension's isMultipartEntity and getParts). Added to Entity by
 * EntityPartsMixin, and for the compile by Loom's interface injection. On Fabric no entity has NeoForge parts (the game's
 * ender dragon parts are its own EnderDragonPart, which NeoForge makes PartEntity): an entity is not multipart and has
 * none, unless its own class says otherwise.
 */
public interface EntityParts {

    default boolean isMultipartEntity() {
        return false;
    }

    default PartEntity<?>[] getParts() {
        return null;
    }
}
