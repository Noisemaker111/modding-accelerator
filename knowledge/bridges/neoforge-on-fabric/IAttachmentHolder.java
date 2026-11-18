package ${package};

import java.util.Optional;
import java.util.function.Supplier;

/**
 * NeoForge's net.neoforged.neoforge.attachment.IAttachmentHolder, which NeoForge's patches make the game's entities, block
 * entities, levels and chunks (mixin/AttachmentHolderMixin here), so a mod's attachment default made from its holder
 * (new X(IAttachmentHolder holder)) gets the entity itself. Each method is Attachments' (Fabric API's attachments).
 */
public interface IAttachmentHolder {

    default <T> T getData(AttachmentType<T> type) {
        return Attachments.getData(this, type);
    }

    default <T> T getData(Supplier<AttachmentType<T>> type) {
        return Attachments.getData(this, type);
    }

    default boolean hasData(AttachmentType<?> type) {
        return Attachments.hasData(this, type);
    }

    default <T> boolean hasData(Supplier<AttachmentType<T>> type) {
        return Attachments.hasData(this, type);
    }

    default <T> T getExistingDataOrNull(AttachmentType<T> type) {
        return Attachments.getExistingDataOrNull(this, type);
    }

    default <T> Optional<T> getExistingData(AttachmentType<T> type) {
        return Attachments.getExistingData(this, type);
    }

    default <T> T setData(AttachmentType<T> type, T data) {
        return Attachments.setData(this, type, data);
    }

    default <T> T setData(Supplier<AttachmentType<T>> type, T data) {
        return Attachments.setData(this, type, data);
    }

    default <T> T removeData(AttachmentType<T> type) {
        return Attachments.removeData(this, type);
    }
}
