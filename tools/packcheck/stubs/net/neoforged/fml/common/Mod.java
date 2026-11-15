package net.neoforged.fml.common;

/** Compile-time stand-in for NeoForge's @Mod: the probe jar carries only the annotation's name; NeoForge supplies the real class. */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
public @interface Mod { String value(); }
