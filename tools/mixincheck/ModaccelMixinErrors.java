package org.spongepowered.asm.mixin.extensibility;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Mixin calls this for every mixin that fails to prepare or apply, in Mixin's own words. It
 * returns WARN so the run carries on and every target class is still loaded and checked.
 *
 * It lives in Mixin's own package and uses nothing outside the JDK and Mixin, because Mixin loads
 * it with Mixin's class loader: under NeoForge that is a module layer that cannot see modaccel's
 * classes, so it is defined there directly. It appends one JSON line per failure to the file
 * named by -Dmodaccel.mixincheck.out, the same file ModaccelMixinCheck writes.
 */
public final class ModaccelMixinErrors implements IMixinErrorHandler {
    @Override public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        report("prepare", null, config, th, mixin, action); return ErrorAction.WARN;
    }
    @Override public ErrorAction onApplyError(String target, Throwable th, IMixinInfo mixin, ErrorAction action) {
        report("apply", target, mixin == null ? null : mixin.getConfig(), th, mixin, action); return ErrorAction.WARN;
    }
    static synchronized void report(String phase, String target, IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        StringBuilder line = new StringBuilder("{\"event\":\"error\"");
        add(line, "phase", phase); add(line, "target", target); add(line, "mixin", mixin == null ? null : mixin.getClassName());
        add(line, "config", config == null ? null : config.getName()); add(line, "configRequired", config != null && config.isRequired());
        add(line, "action", action == null ? null : action.name()); add(line, "exception", th.getClass().getName()); add(line, "message", messages(th));
        // An injector's own counts: what it expected, needed and actually did, from Mixin's injection object.
        try {
            Object context = th.getClass().getMethod("getContext").invoke(th);
            if (context != null) {
                for (String name : new String[] {"expectedCallbackCount", "requiredCallbackCount", "injectedCallbackCount", "targetCount"}) {
                    Field field = find(context.getClass(), name);
                    if (field != null) { field.setAccessible(true); add(line, name.replace("CallbackCount", ""), field.get(context)); }
                }
                // Whether the mod's own annotation sets require (an injection it marks optional), or it came from the config.
                Field annotation = find(context.getClass(), "annotation");
                if (annotation != null) {
                    annotation.setAccessible(true); Object node = annotation.get(context);
                    if (node != null) {
                        List<?> values = (List<?>) node.getClass().getField("values").get(node);
                        boolean declared = false;
                        if (values != null) for (int i = 0; i + 1 < values.size(); i += 2) if ("require".equals(values.get(i))) declared = true;
                        add(line, "requireDeclared", declared);
                    }
                }
                Field method = find(context.getClass(), "method");
                if (method != null) {
                    method.setAccessible(true); Object node = method.get(context);
                    if (node != null) add(line, "handler", node.getClass().getField("name").get(node));
                }
            }
        } catch (Throwable ignored) { }
        line.append('}');
        try (Writer out = new OutputStreamWriter(new FileOutputStream(System.getProperty("modaccel.mixincheck.out"), true), "UTF-8")) { out.write(line.append('\n').toString()); }
        catch (IOException failure) { System.err.println("modaccel mixin check could not record: " + line); }
    }
    static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { return c.getDeclaredField(name); } catch (NoSuchFieldException next) { }
        return null;
    }
    static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder(); Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
            if (text.indexOf(message) >= 0) continue;
            if (text.length() > 0) text.append("\n  caused by ").append(t.getClass().getSimpleName()).append(": ");
            text.append(message);
        }
        return text.toString();
    }
    static void add(StringBuilder line, String key, Object value) {
        line.append(',').append(quote(key)).append(':');
        if (value == null) line.append("null");
        else if (value instanceof Number || value instanceof Boolean) line.append(value);
        else line.append(quote(value.toString()));
    }
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"') quoted.append("\\\"");
            else if (c == '\\') quoted.append("\\\\");
            else if (c == '\n') quoted.append("\\n");
            else if (c == '\r') quoted.append("\\r");
            else if (c == '\t') quoted.append("\\t");
            else if (c < 0x20) quoted.append(String.format("\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
