package ${package}.fabric.api.attachment.v1;

import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/**
 * Fabric API's net.fabricmc.fabric.api.attachment.v1.AttachmentType: a Fabric attachment type and the NeoForge
 * attachment type that holds its values (neoforge(), the bridge's own), registered in NeoForge's attachment type
 * registry under the same id (see AttachmentRegistry).
 */
public interface AttachmentType<A> {

    Identifier identifier();

    Codec<A> persistenceCodec();

    default boolean isPersistent() {
        return persistenceCodec() != null;
    }

    Supplier<A> initializer();

    boolean isSynced();

    boolean copyOnDeath();

    /** The NeoForge attachment type holding this type's values. */
    net.neoforged.neoforge.attachment.AttachmentType<A> neoforge();
}
