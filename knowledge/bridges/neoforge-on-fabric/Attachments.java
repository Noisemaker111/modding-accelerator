package ${package};

import java.util.Optional;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;

/**
 * NeoForge's IAttachmentHolder methods (getData, hasData, setData, removeData, getExistingData, getExistingDataOrNull),
 * which NeoForge adds to entities, block entities, levels and chunks, as calls on Fabric API's AttachmentTarget, which
 * Fabric API adds to the same game classes. convert writes holder.getData(TYPE) as Attachments.getData(holder, TYPE)
 * when TYPE is a field the mod declares as an AttachmentType. getData makes the default value for that holder and keeps
 * it, as NeoForge does; setData and removeData return the value they replace.
 */
public final class Attachments {

    private Attachments() {}

    static AttachmentTarget target(Object holder) {
        return (AttachmentTarget) holder;
    }

    public static <T> T getData(Object holder, AttachmentType<T> type) {
        return target(holder).getAttachedOrCreate(type.fabric(), () -> type.create(holder));
    }

    public static <T> T getData(Object holder, Supplier<AttachmentType<T>> type) {
        return getData(holder, type.get());
    }

    public static boolean hasData(Object holder, AttachmentType<?> type) {
        return target(holder).hasAttached(type.fabric());
    }

    public static <T> boolean hasData(Object holder, Supplier<AttachmentType<T>> type) {
        return hasData(holder, type.get());
    }

    public static <T> T getExistingDataOrNull(Object holder, AttachmentType<T> type) {
        return target(holder).getAttached(type.fabric());
    }

    public static <T> T getExistingDataOrNull(Object holder, Supplier<AttachmentType<T>> type) {
        return getExistingDataOrNull(holder, type.get());
    }

    public static <T> Optional<T> getExistingData(Object holder, AttachmentType<T> type) {
        return Optional.ofNullable(getExistingDataOrNull(holder, type));
    }

    public static <T> Optional<T> getExistingData(Object holder, Supplier<AttachmentType<T>> type) {
        return getExistingData(holder, type.get());
    }

    public static <T> T setData(Object holder, AttachmentType<T> type, T data) {
        return target(holder).setAttached(type.fabric(), data);
    }

    public static <T> T setData(Object holder, Supplier<AttachmentType<T>> type, T data) {
        return setData(holder, type.get(), data);
    }

    public static <T> T removeData(Object holder, AttachmentType<T> type) {
        return target(holder).removeAttached(type.fabric());
    }

    public static <T> T removeData(Object holder, Supplier<AttachmentType<T>> type) {
        return removeData(holder, type.get());
    }
}
