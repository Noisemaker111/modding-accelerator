package ${package};

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * NeoForge's net.neoforged.neoforge.common.ModConfigSpec on Fabric: the same builder and values, kept in the same
 * TOML file NeoForge writes (config/MODID-TYPE.toml unless the mod names one). A missing or invalid value takes its
 * default and the file is written back corrected, as NeoForge corrects it. Translation keys and restart flags only
 * matter to NeoForge's config screen, which Fabric does not have, and are kept but unused.
 */
public class ModConfigSpec {

    final List<ConfigValue<?>> values;
    final Map<List<String>, String> levelComments;
    Path file;
    boolean loaded;

    ModConfigSpec(List<ConfigValue<?>> values, Map<List<String>, String> levelComments) {
        this.values = values;
        this.levelComments = levelComments;
        for (ConfigValue<?> value : values) value.spec = this;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String getLevelComment(List<String> path) {
        return levelComments.get(path);
    }

    public void afterReload() {}

    /** Reads the file (or writes it with the defaults when there is none); values it lacks or rejects take their defaults. */
    void load(Path file) {
        this.file = file;
        Map<List<String>, Object> raw = new LinkedHashMap<>();
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            try {
                raw = Toml.read(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException unreadable) {
                raw = new LinkedHashMap<>();
                exists = false;
            }
        }
        boolean corrected = !exists;
        for (ConfigValue<?> value : values) corrected |= !value.accept(raw.get(value.path));
        loaded = true;
        if (corrected) save();
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Toml.write(this), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw new IllegalStateException("Could not write config " + file, unwritable);
        }
    }

    static List<String> split(String path) {
        return Arrays.asList(path.split("\\."));
    }

    /** NeoForge's ModConfigSpec.Builder. */
    public static class Builder {

        final List<ConfigValue<?>> values = new ArrayList<>();
        final Map<List<String>, String> levelComments = new LinkedHashMap<>();
        final List<String> current = new ArrayList<>();
        String[] comment = new String[0];

        List<String> path(List<String> relative) {
            List<String> out = new ArrayList<>(current);
            out.addAll(relative);
            return out;
        }

        <V extends ConfigValue<?>> V add(V value) {
            values.add(value);
            comment = new String[0];
            return value;
        }

        String takeComment() {
            return String.join("\n", comment);
        }

        public <T> ConfigValue<T> define(String path, T defaultValue) {
            return define(split(path), defaultValue);
        }

        public <T> ConfigValue<T> define(List<String> path, T defaultValue) {
            return define(path, defaultValue, o -> o != null && defaultValue.getClass().isAssignableFrom(o.getClass()));
        }

        public <T> ConfigValue<T> define(String path, T defaultValue, Predicate<Object> validator) {
            return define(split(path), defaultValue, validator);
        }

        public <T> ConfigValue<T> define(List<String> path, T defaultValue, Predicate<Object> validator) {
            return define(path, () -> defaultValue, validator);
        }

        public <T> ConfigValue<T> define(String path, Supplier<T> defaultSupplier, Predicate<Object> validator) {
            return define(split(path), defaultSupplier, validator);
        }

        public <T> ConfigValue<T> define(List<String> path, Supplier<T> defaultSupplier, Predicate<Object> validator) {
            return add(new ConfigValue<>(this, path(path), defaultSupplier, validator, takeComment(), null));
        }

        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(String path, V defaultValue, V min, V max, Class<V> type) {
            return defineInRange(split(path), () -> defaultValue, min, max, type);
        }

        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(List<String> path, Supplier<V> defaultSupplier, V min, V max, Class<V> type) {
            return add(new ConfigValue<>(this, path(path), defaultSupplier, o -> type.isInstance(o) && min.compareTo(type.cast(o)) <= 0 && max.compareTo(type.cast(o)) >= 0, takeComment(), "Range: " + min + " ~ " + max));
        }

        public <T> ConfigValue<T> defineInList(String path, T defaultValue, Collection<? extends T> acceptable) {
            return define(split(path), () -> defaultValue, acceptable::contains);
        }

        public <T> ConfigValue<T> defineInList(List<String> path, T defaultValue, Collection<? extends T> acceptable) {
            return define(path, () -> defaultValue, acceptable::contains);
        }

        public <T> ConfigValue<List<? extends T>> defineList(String path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(split(path), () -> defaultValue, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineList(String path, List<? extends T> defaultValue, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(split(path), () -> defaultValue, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineList(String path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultSupplier, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineList(String path, Supplier<List<? extends T>> defaultSupplier, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultSupplier, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineList(List<String> path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(path, () -> defaultValue, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineList(List<String> path, Supplier<List<? extends T>> defaultSupplier, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(path, defaultSupplier, elementValidator, false);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(split(path), () -> defaultValue, elementValidator, true);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String path, List<? extends T> defaultValue, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(split(path), () -> defaultValue, elementValidator, true);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultSupplier, elementValidator, true);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String path, Supplier<List<? extends T>> defaultSupplier, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultSupplier, elementValidator, true);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(List<String> path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(path, () -> defaultValue, elementValidator, true);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(List<String> path, Supplier<List<? extends T>> defaultSupplier, Supplier<T> newElement, Predicate<Object> elementValidator) {
            return defineList(path, defaultSupplier, elementValidator, true);
        }

        <T> ConfigValue<List<? extends T>> defineList(List<String> path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator, boolean allowEmpty) {
            Predicate<Object> valid = o -> o instanceof List<?> list && (allowEmpty || !list.isEmpty()) && list.stream().allMatch(elementValidator);
            return add(new ConfigValue<>(this, path(path), defaultSupplier, valid, takeComment(), null));
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue) {
            return defineEnum(split(path), defaultValue);
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue) {
            return defineEnum(path, defaultValue, o -> true);
        }

        @SafeVarargs
        public final <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, V... acceptable) {
            return defineEnum(split(path), defaultValue, Arrays.asList(acceptable));
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, Collection<V> acceptable) {
            return defineEnum(split(path), defaultValue, acceptable);
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, Collection<V> acceptable) {
            return defineEnum(path, defaultValue, acceptable::contains);
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, Predicate<Object> validator) {
            return defineEnum(split(path), defaultValue, validator);
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, Predicate<Object> validator) {
            Class<V> type = defaultValue.getDeclaringClass();
            return add(new EnumValue<>(this, path(path), () -> defaultValue, o -> type.isInstance(o) && validator.test(o), takeComment(), type));
        }

        public BooleanValue define(String path, boolean defaultValue) {
            return define(split(path), defaultValue);
        }

        public BooleanValue define(List<String> path, boolean defaultValue) {
            return define(path, () -> defaultValue);
        }

        public BooleanValue define(String path, Supplier<Boolean> defaultSupplier) {
            return define(split(path), defaultSupplier);
        }

        public BooleanValue define(List<String> path, Supplier<Boolean> defaultSupplier) {
            return add(new BooleanValue(this, path(path), defaultSupplier, o -> o instanceof Boolean, takeComment()));
        }

        public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
            return defineInRange(split(path), () -> defaultValue, min, max);
        }

        public DoubleValue defineInRange(List<String> path, double defaultValue, double min, double max) {
            return defineInRange(path, () -> defaultValue, min, max);
        }

        public DoubleValue defineInRange(String path, Supplier<Double> defaultSupplier, double min, double max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }

        public DoubleValue defineInRange(List<String> path, Supplier<Double> defaultSupplier, double min, double max) {
            return add(new DoubleValue(this, path(path), defaultSupplier, o -> o instanceof Double d && d >= min && d <= max, takeComment(), "Range: " + min + " ~ " + max));
        }

        public IntValue defineInRange(String path, int defaultValue, int min, int max) {
            return defineInRange(split(path), () -> defaultValue, min, max);
        }

        public IntValue defineInRange(List<String> path, int defaultValue, int min, int max) {
            return defineInRange(path, () -> defaultValue, min, max);
        }

        public IntValue defineInRange(String path, Supplier<Integer> defaultSupplier, int min, int max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }

        public IntValue defineInRange(List<String> path, Supplier<Integer> defaultSupplier, int min, int max) {
            return add(new IntValue(this, path(path), defaultSupplier, o -> o instanceof Integer i && i >= min && i <= max, takeComment(), "Range: " + (min == Integer.MIN_VALUE ? "< " + max : max == Integer.MAX_VALUE ? "> " + min : min + " ~ " + max)));
        }

        public LongValue defineInRange(String path, long defaultValue, long min, long max) {
            return defineInRange(split(path), () -> defaultValue, min, max);
        }

        public LongValue defineInRange(List<String> path, long defaultValue, long min, long max) {
            return defineInRange(path, () -> defaultValue, min, max);
        }

        public LongValue defineInRange(String path, Supplier<Long> defaultSupplier, long min, long max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }

        public LongValue defineInRange(List<String> path, Supplier<Long> defaultSupplier, long min, long max) {
            return add(new LongValue(this, path(path), defaultSupplier, o -> o instanceof Long l && l >= min && l <= max, takeComment(), "Range: " + min + " ~ " + max));
        }

        public Builder comment(String comment) {
            this.comment = new String[] { comment };
            return this;
        }

        public Builder comment(String... comment) {
            this.comment = comment;
            return this;
        }

        public Builder translation(String translationKey) {
            return this;
        }

        public Builder worldRestart() {
            return this;
        }

        public Builder gameRestart() {
            return this;
        }

        public Builder push(String path) {
            return push(split(path));
        }

        public Builder push(List<String> path) {
            current.addAll(path);
            if (comment.length > 0) levelComments.put(new ArrayList<>(current), takeComment());
            comment = new String[0];
            return this;
        }

        public Builder pop() {
            return pop(1);
        }

        public Builder pop(int count) {
            for (int i = 0; i < count; i++) current.remove(current.size() - 1);
            return this;
        }

        public <T> org.apache.commons.lang3.tuple.Pair<T, ModConfigSpec> configure(Function<Builder, T> consumer) {
            T result = consumer.apply(this);
            return org.apache.commons.lang3.tuple.Pair.of(result, build());
        }

        public ModConfigSpec build() {
            return new ModConfigSpec(new ArrayList<>(values), new LinkedHashMap<>(levelComments));
        }
    }

    /** NeoForge's ModConfigSpec.ConfigValue. */
    public static class ConfigValue<T> implements Supplier<T> {

        final Builder parent;
        final List<String> path;
        final Supplier<T> defaultSupplier;
        final Predicate<Object> validator;
        final String comment, range;
        ModConfigSpec spec;
        T value;

        ConfigValue(Builder parent, List<String> path, Supplier<T> defaultSupplier, Predicate<Object> validator, String comment, String range) {
            this.parent = parent;
            this.path = List.copyOf(path);
            this.defaultSupplier = defaultSupplier;
            this.validator = validator;
            this.comment = comment;
            this.range = range;
        }

        public List<String> getPath() {
            return path;
        }

        public T get() {
            return value != null ? value : getDefault();
        }

        public T getRaw() {
            return get();
        }

        public T getDefault() {
            return defaultSupplier.get();
        }

        public Builder next() {
            return parent;
        }

        public void save() {
            if (spec != null) spec.save();
        }

        public void set(T value) {
            this.value = value;
        }

        public void clearCache() {}

        /** The file's value in this value's type, or null when it cannot be one. */
        @SuppressWarnings("unchecked")
        Object convert(Object raw) {
            T fallback = getDefault();
            if (raw == null) return null;
            if (fallback instanceof Integer && raw instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
            if (fallback instanceof Double && raw instanceof Number n) return n.doubleValue();
            if (fallback instanceof Float && raw instanceof Number n) return n.floatValue();
            return raw;
        }

        /** Takes the file's value when it is valid; false when the default had to stand in. */
        @SuppressWarnings("unchecked")
        boolean accept(Object raw) {
            Object converted = convert(raw);
            if (converted != null && validator.test(converted)) {
                value = (T) converted;
                return true;
            }
            value = getDefault();
            return false;
        }
    }

    public static class BooleanValue extends ConfigValue<Boolean> implements BooleanSupplier {

        BooleanValue(Builder parent, List<String> path, Supplier<Boolean> defaultSupplier, Predicate<Object> validator, String comment) {
            super(parent, path, defaultSupplier, validator, comment, null);
        }

        @Override
        public boolean getAsBoolean() {
            return get();
        }

        public boolean isTrue() {
            return getAsBoolean();
        }

        public boolean isFalse() {
            return !getAsBoolean();
        }
    }

    public static class IntValue extends ConfigValue<Integer> implements IntSupplier {

        IntValue(Builder parent, List<String> path, Supplier<Integer> defaultSupplier, Predicate<Object> validator, String comment, String range) {
            super(parent, path, defaultSupplier, validator, comment, range);
        }

        @Override
        public int getAsInt() {
            return get();
        }
    }

    public static class LongValue extends ConfigValue<Long> implements LongSupplier {

        LongValue(Builder parent, List<String> path, Supplier<Long> defaultSupplier, Predicate<Object> validator, String comment, String range) {
            super(parent, path, defaultSupplier, validator, comment, range);
        }

        @Override
        public long getAsLong() {
            return get();
        }
    }

    public static class DoubleValue extends ConfigValue<Double> implements DoubleSupplier {

        DoubleValue(Builder parent, List<String> path, Supplier<Double> defaultSupplier, Predicate<Object> validator, String comment, String range) {
            super(parent, path, defaultSupplier, validator, comment, range);
        }

        @Override
        public double getAsDouble() {
            return get();
        }
    }

    public static class EnumValue<T extends Enum<T>> extends ConfigValue<T> {

        final Class<T> type;

        EnumValue(Builder parent, List<String> path, Supplier<T> defaultSupplier, Predicate<Object> validator, String comment, Class<T> type) {
            super(parent, path, defaultSupplier, validator, comment, null);
            this.type = type;
        }

        @Override
        Object convert(Object raw) {
            if (raw instanceof String name) for (T constant : type.getEnumConstants()) if (constant.name().equalsIgnoreCase(name)) return constant;
            return null;
        }
    }

    /** The TOML NeoForge's config files use, as far as these values need it: tables, keys, scalars and flat arrays. */
    static final class Toml {

        static String write(ModConfigSpec spec) {
            Map<List<String>, List<ConfigValue<?>>> tables = new LinkedHashMap<>();
            for (ConfigValue<?> value : spec.values) tables.computeIfAbsent(value.path.subList(0, value.path.size() - 1), k -> new ArrayList<>()).add(value);
            StringBuilder out = new StringBuilder();
            for (var table : tables.entrySet()) {
                if (!table.getKey().isEmpty()) {
                    if (out.length() > 0) out.append('\n');
                    String level = spec.levelComments.get(table.getKey());
                    if (level != null && !level.isBlank()) for (String line : level.split("\n")) out.append('#').append(line).append('\n');
                    out.append('[').append(String.join(".", table.getKey().stream().map(Toml::key).toList())).append("]\n");
                }
                for (ConfigValue<?> value : table.getValue()) {
                    if (!value.comment.isBlank()) for (String line : value.comment.split("\n")) out.append('#').append(line).append('\n');
                    if (value.range != null) out.append('#').append(value.range).append('\n');
                    out.append(key(value.path.get(value.path.size() - 1))).append(" = ").append(format(value.get())).append('\n');
                }
            }
            return out.toString();
        }

        static String key(String key) {
            return key.matches("[A-Za-z0-9_-]+") ? key : quote(key);
        }

        static String quote(String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        static String format(Object value) {
            if (value instanceof String s) return quote(s);
            if (value instanceof Enum<?> e) return quote(e.name());
            if (value instanceof List<?> list) return "[" + String.join(", ", list.stream().map(Toml::format).toList()) + "]";
            return String.valueOf(value);
        }

        static Map<List<String>, Object> read(String text) {
            Map<List<String>, Object> out = new LinkedHashMap<>();
            List<String> table = new ArrayList<>();
            StringBuilder pending = new StringBuilder();
            for (String raw : text.split("\r?\n")) {
                String line = stripComment(raw).strip();
                if (pending.length() > 0) {
                    pending.append(' ').append(line);
                    if (depth(pending) > 0) continue;
                    line = pending.toString();
                    pending.setLength(0);
                }
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && !line.contains("=")) {
                    table = new ArrayList<>();
                    for (String part : line.substring(1, line.lastIndexOf(']')).split("\\.")) table.add(unquote(part.strip()));
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (depth(new StringBuilder(line)) > 0) {
                    pending.append(line);
                    continue;
                }
                List<String> path = new ArrayList<>(table);
                path.add(unquote(line.substring(0, eq).strip()));
                out.put(path, value(line.substring(eq + 1).strip()));
            }
            return out;
        }

        static String stripComment(String line) {
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\' && quoted) i++;
                else if (c == '"') quoted = !quoted;
                else if (c == '#' && !quoted) return line.substring(0, i);
            }
            return line;
        }

        static int depth(CharSequence text) {
            int depth = 0;
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && quoted) i++;
                else if (c == '"') quoted = !quoted;
                else if (!quoted && c == '[') depth++;
                else if (!quoted && c == ']') depth--;
            }
            return depth;
        }

        static String unquote(String text) {
            if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
                return text.substring(1, text.length() - 1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return text;
        }

        static Object value(String text) {
            if (text.startsWith("[")) {
                List<Object> list = new ArrayList<>();
                String inner = text.substring(1, text.lastIndexOf(']')).strip();
                StringBuilder item = new StringBuilder();
                boolean quoted = false;
                for (int i = 0; i < inner.length(); i++) {
                    char c = inner.charAt(i);
                    if (c == '\\' && quoted && i + 1 < inner.length()) {
                        item.append(c).append(inner.charAt(++i));
                        continue;
                    }
                    if (c == '"') quoted = !quoted;
                    if (c == ',' && !quoted) {
                        if (!item.toString().isBlank()) list.add(value(item.toString().strip()));
                        item.setLength(0);
                    } else item.append(c);
                }
                if (!item.toString().isBlank()) list.add(value(item.toString().strip()));
                return list;
            }
            if (text.startsWith("\"") || text.startsWith("'")) return unquote(text);
            if (text.equals("true") || text.equals("false")) return Boolean.parseBoolean(text);
            try {
                if (text.matches("[+-]?\\d+")) return Long.parseLong(text);
                return Double.parseDouble(text);
            } catch (NumberFormatException notANumber) {
                return text.toLowerCase(Locale.ROOT);
            }
        }
    }
}
