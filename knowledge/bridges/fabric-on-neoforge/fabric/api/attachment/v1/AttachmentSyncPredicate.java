package ${package}.fabric.api.attachment.v1;

import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * Fabric API's net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate: which players get a synced attachment's
 * value, asked by NeoForge's attachment sync. The target is NeoForge's attachment holder (the entity, block entity,
 * chunk or level), where Fabric names it AttachmentTarget, an interface Fabric adds to those same classes.
 */
@FunctionalInterface
public interface AttachmentSyncPredicate extends BiPredicate<IAttachmentHolder, ServerPlayer> {

    static AttachmentSyncPredicate all() {
        return (target, player) -> true;
    }

    static AttachmentSyncPredicate targetOnly() {
        return (target, player) -> target == player;
    }

    static AttachmentSyncPredicate allButTarget() {
        return (target, player) -> target != player;
    }
}
