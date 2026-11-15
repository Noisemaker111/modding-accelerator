package ${package};

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * FancyModLoader's net.neoforged.fml.util.ObfuscationReflectionHelper: reflection by the game's own names, made
 * accessible, with NeoForge's exceptions. NeoForge runs the game under Mojang's names, and so does Fabric from Minecraft
 * 26.1 (an unobfuscated game). Before 26.1 a Fabric game runs under intermediary names outside development, which a
 * Mojang name does not find there: the lookup then fails as NeoForge's does for a missing member.
 */
public final class ObfuscationReflectionHelper {

    private ObfuscationReflectionHelper() {}

    @SuppressWarnings("unchecked")
    public static <T, E> T getPrivateValue(Class<? super E> classToAccess, E instance, String fieldName) {
        try {
            return (T) findField(classToAccess, fieldName).get(instance);
        } catch (IllegalAccessException e) {
            throw new UnableToAccessFieldException(e);
        }
    }

    public static <T, E> void setPrivateValue(Class<? super T> classToAccess, T instance, E value, String fieldName) {
        try {
            findField(classToAccess, fieldName).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new UnableToAccessFieldException(e);
        }
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Objects.requireNonNull(clazz, "Class to find method on cannot be null.");
        Objects.requireNonNull(methodName, "Name of method to find cannot be null.");
        if (methodName.isEmpty()) throw new IllegalArgumentException("Name of method to find cannot be empty.");
        try {
            Method m = clazz.getDeclaredMethod(methodName, parameterTypes);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            throw new UnableToFindMethodException(e);
        }
    }

    public static <T> Constructor<T> findConstructor(Class<T> clazz, Class<?>... parameterTypes) {
        Objects.requireNonNull(clazz, "Class to find constructor on cannot be null.");
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new UnknownConstructorException("Could not find constructor of " + clazz.getSimpleName() + " in " + clazz);
        }
    }

    public static <T> Field findField(Class<? super T> clazz, String fieldName) {
        Objects.requireNonNull(clazz, "Class to find field on cannot be null.");
        Objects.requireNonNull(fieldName, "Name of field to find cannot be null.");
        if (fieldName.isEmpty()) throw new IllegalArgumentException("Name of field to find cannot be empty.");
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            throw new UnableToFindFieldException(e);
        }
    }

    public static class UnableToAccessFieldException extends RuntimeException {
        private UnableToAccessFieldException(Exception e) {
            super(e);
        }
    }

    public static class UnableToFindFieldException extends RuntimeException {
        private UnableToFindFieldException(Exception e) {
            super(e);
        }
    }

    public static class UnableToFindMethodException extends RuntimeException {
        public UnableToFindMethodException(Throwable failed) {
            super(failed);
        }
    }

    public static class UnknownConstructorException extends RuntimeException {
        public UnknownConstructorException(String message) {
            super(message);
        }
    }
}
