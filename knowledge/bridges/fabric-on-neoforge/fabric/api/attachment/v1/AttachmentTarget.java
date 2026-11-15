package ${package}.fabric.api.attachment.v1;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * Fabric API's net.fabricmc.fabric.api.attachment.v1.AttachmentTarget. On Fabric its methods are added to the game's
 * entities, block entities, chunks and levels; on NeoForge those are attachment holders (IAttachmentHolder), so convert
 * writes each call target.getAttached(type) as AttachmentTarget.getAttached(target, type), and these read and write the
 * NeoForge attachment that holds the type's values, with Fabric's meaning: no value is null (getAttached never makes
 * one; getAttachedOrCreate makes the type's initial value), and setting null removes the value.
 */
public interface AttachmentTarget {

    String NBT_ATTACHMENT_KEY = "fabric:attachments";

    static <A> A getAttached(IAttachmentHolder target, AttachmentType<A> type) {
        return target.getExistingDataOrNull(type.neoforge());
    }

    static <A> A getAttachedOrThrow(IAttachmentHolder target, AttachmentType<A> type) {
        return Objects.requireNonNull(getAttached(target, type), "No value was attached");
    }

    static <A> A getAttachedOrSet(IAttachmentHolder target, AttachmentType<A> type, A defaultValue) {
        Objects.requireNonNull(defaultValue, "default value cannot be null");
        A attached = getAttached(target, type);
        if (attached != null) return attached;
        setAttached(target, type, defaultValue);
        return defaultValue;
    }

    static <A> A getAttachedOrCreate(IAttachmentHolder target, AttachmentType<A> type, Supplier<A> initializer) {
        A attached = getAttached(target, type);
        if (attached != null) return attached;
        A initialized = Objects.requireNonNull(initializer.get(), "initializer result cannot be null");
        setAttached(target, type, initialized);
        return initialized;
    }

    static <A> A getAttachedOrCreate(IAttachmentHolder target, AttachmentType<A> type) {
        Supplier<A> init = type.initializer();
        if (init == null) throw new IllegalArgumentException("Single-argument getAttachedOrCreate is reserved for attachment types with default initializers");
        return getAttachedOrCreate(target, type, init);
    }

    static <A> A getAttachedOrElse(IAttachmentHolder target, AttachmentType<A> type, A defaultValue) {
        A attached = getAttached(target, type);
        return attached == null ? defaultValue : attached;
    }

    static <A> A getAttachedOrGet(IAttachmentHolder target, AttachmentType<A> type, Supplier<A> defaultValue) {
        Objects.requireNonNull(defaultValue, "default value supplier cannot be null");
        A attached = getAttached(target, type);
        return attached == null ? defaultValue.get() : attached;
    }

    static <A> A setAttached(IAttachmentHolder target, AttachmentType<A> type, A value) {
        if (value == null) return target.removeData(type.neoforge());
        return target.setData(type.neoforge(), value);
    }

    static boolean hasAttached(IAttachmentHolder target, AttachmentType<?> type) {
        return target.hasData(type.neoforge());
    }

    static <A> A removeAttached(IAttachmentHolder target, AttachmentType<A> type) {
        return setAttached(target, type, null);
    }

    static <A> A modifyAttached(IAttachmentHolder target, AttachmentType<A> type, UnaryOperator<A> modifier) {
        return setAttached(target, type, modifier.apply(getAttached(target, type)));
    }
}
