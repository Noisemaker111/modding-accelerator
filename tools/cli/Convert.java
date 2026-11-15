import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Goal 4: moves a mod's source between Fabric and NeoForge on the same Minecraft version. Every
 * change comes from knowledge/loaders.json or from the fixed steps here (metadata, entrypoints,
 * build); nothing is guessed. What has no rule is named with its file and line and left as it was,
 * never dropped. Each run is one transaction that convert --undo restores byte for byte.
 */
final class Convert {

    static final String FOLDER = "converts";

    final Build build;
    final ProjectModel project;
    Map<String, Object> rules;
    Path transaction;
    final Map<String, Object> before = new LinkedHashMap<>();
    final List<String> removed = new ArrayList<>(), changed = new ArrayList<>(), notes = new ArrayList<>();
    final List<Map<String, Object>> left = new ArrayList<>();
    final Map<String, List<String>> applied = new TreeMap<>();
    /** Access transformer lines the rewritten code needs on NeoForge. */
    final Set<String> accessTransformers = new LinkedHashSet<>();
    static final String AT_FILE = "src/main/resources/META-INF/accesstransformer.cfg";
    String modid = "", fabricId = "", unit = "\t";

    /** A Fabric mod id as NeoForge accepts it (^[a-z][a-z0-9_]{1,63}$): Fabric also allows "-". */
    static String neoforgeModId(String id) {
        String out = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (out.isEmpty() || !Character.isLetter(out.charAt(0))) out = "mod_" + out;
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    Convert(Build build) {
        this.build = build;
        this.project = build.project;
    }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        if (options.values.containsKey("undo")) return Port.undo(project, options.get("undo", ""), FOLDER);
        String to = options.get("to", "").toLowerCase(Locale.ROOT);
        String from = loader(project.root);
        if (from == null) throw new IOException(
            "convert found neither src/main/resources/fabric.mod.json nor src/main/resources/META-INF/neoforge.mods.toml in " +
            project.root +
            ". It converts one-module Fabric and NeoForge mods."
        );
        if (!Set.of("fabric", "neoforge").contains(to)) throw new IllegalArgumentException(
            "convert needs --to fabric or --to neoforge. Forge, Quilt and Paper are not converted yet; see modaccel help convert."
        );
        if (to.equals(from)) throw new IllegalArgumentException("This project is already a " + from + " mod.");
        if (
            Files.isRegularFile(project.root.resolve("build.gradle.kts")) ||
            !Files.isRegularFile(project.root.resolve("build.gradle"))
        ) {
            throw new IOException(
                "convert rewrites a Groovy build.gradle; this project has " +
                (Files.isRegularFile(project.root.resolve("build.gradle.kts"))
                        ? "build.gradle.kts"
                        : "no build.gradle") +
                "."
            );
        }
        rules = Json.read(build.harness.resolve("knowledge/loaders.json"));
        String minecraft = properties(read("gradle.properties")).getOrDefault("minecraft_version", "");
        if (minecraft.isEmpty()) throw new IOException(
            "convert reads the Minecraft version from minecraft_version in gradle.properties; there is none."
        );
        transaction = project.state().resolve(FOLDER).resolve(Instant.now().toString().replace(':', '-'));
        Files.createDirectories(transaction.resolve("before"));
        saveInProgress();
        if (from.equals("fabric")) toNeoForge(minecraft);
        else toFabric(minecraft);
        Port.finishTransaction(project, transaction, before, removed);
        Map<String, Object> result = Json.map(
            "from",
            from,
            "to",
            to,
            "minecraft",
            minecraft,
            "changed",
            changed,
            "removed",
            removed,
            "rulesApplied",
            applied,
            "left",
            left,
            "notes",
            notes,
            "undoConvert",
            transaction.getFileName().toString(),
            "transaction",
            transaction.toString()
        );
        if (options.has("dry-run")) {
            result.put("compiled", null);
            result.put("ok", left.isEmpty());
        } else compile(result);
        result.put("details", text(result));
        return result;
    }

    /** The project's loader by its metadata file; null when it has neither. */
    static String loader(Path root) {
        if (Files.isRegularFile(root.resolve("src/main/resources/fabric.mod.json"))) return "fabric";
        if (Files.isRegularFile(root.resolve("src/main/resources/META-INF/neoforge.mods.toml"))) return "neoforge";
        return null;
    }

    // ---- the transaction ----------------------------------------------------------------------------

    void saveInProgress() throws IOException {
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                before,
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map("edits", new ArrayList<>(before.keySet()), "removals", removed),
                "status",
                "in-progress"
            )
        );
    }

    String relative(Path file) {
        return project.root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    void track(Path file) throws Exception {
        String relative = relative(file);
        if (before.containsKey(relative)) return;
        if (Files.isRegularFile(file)) {
            Path backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            before.put(relative, Fingerprints.bytes(Files.readAllBytes(file)));
        } else before.put(relative, null);
        saveInProgress();
    }

    /** Files whose lines end in CRLF: they are read with LF and written back with CRLF. */
    final Set<String> crlf = new HashSet<>();

    String read(String relative) throws IOException {
        Path file = project.root.resolve(relative);
        if (!Files.isRegularFile(file)) return "";
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.contains("\r\n")) crlf.add(relative);
        return text.replace("\r\n", "\n");
    }

    void write(String relative, String text) throws Exception {
        Path file = project.root.resolve(relative);
        if (crlf.contains(relative) || (!Files.isRegularFile(file) && !crlf.isEmpty())) text = text
            .replace("\r\n", "\n")
            .replace("\n", "\r\n");
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
        track(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        if (!changed.contains(relative)) changed.add(relative);
    }

    void delete(String relative) throws Exception {
        Path file = project.root.resolve(relative);
        if (!Files.isRegularFile(file)) return;
        track(file);
        removed.add(relative);
        saveInProgress();
        Files.delete(file);
        changed.remove(relative);
        for (
            Path parent = file.getParent();
            parent != null && !parent.equals(project.root);
            parent = parent.getParent()
        ) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            }
            Files.delete(parent);
        }
    }

    void leave(String file, int line, String what, String why) {
        left.add(Json.map("file", file, "line", line, "what", what, "why", why));
    }

    // ---- sources -------------------------------------------------------------------------------------

    List<String> javaFiles() throws IOException {
        List<String> out = new ArrayList<>();
        Path src = project.root.resolve("src");
        if (!Files.isDirectory(src)) return out;
        try (var sets = Files.list(src)) {
            for (Path set : sets.sorted().toList()) {
                if (set.getFileName().toString().startsWith("test")) continue;
                Path java = set.resolve("java");
                if (!Files.isDirectory(java)) continue;
                try (var walk = Files.walk(java)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) out.add(
                        relative(file)
                    );
                }
            }
        }
        return out;
    }

    /** The source file of a class, in any source set; null when none has it. */
    String classFile(String name) throws IOException {
        String path = name.replace('.', '/') + ".java";
        for (String file : javaFiles()) if (file.endsWith("/java/" + path)) return file;
        return null;
    }

    void rewriteSources(String from, String to) throws Exception {
        for (String file : javaFiles()) {
            String text = read(file);
            LoaderRules.Result result = new LoaderRules(rules, from, to, modid).apply(text);
            for (String id : result.applied) applied.computeIfAbsent(id, k -> new ArrayList<>()).add(file);
            for (LoaderRules.Left l : result.left) leave(file, l.line(), l.what(), l.why());
            accessTransformers.addAll(result.accessTransformers);
            write(file, result.text);
        }
    }

    /** Names every use of the old loader that no rule changed: the class and where. */
    void leftovers(List<String> prefixes) throws Exception {
        for (String file : javaFiles()) {
            String text = read(file);
            Matcher m = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)").matcher(text);
            while (m.find()) for (String prefix : prefixes) if (m.group(1).startsWith(prefix)) leave(
                file,
                LoaderRules.line(text, m.start()),
                m.group(1),
                "no rule in knowledge/loaders.json moves this to the other loader yet"
            );
        }
    }

    // ---- Fabric to NeoForge ----------------------------------------------------------------------------

    void toNeoForge(String minecraft) throws Exception {
        String metaFile = "src/main/resources/fabric.mod.json";
        Map<String, Object> meta = Json.object(Json.parse(read(metaFile)));
        fabricId = Json.text(meta, "id", "");
        if (fabricId.isEmpty()) throw new IOException("fabric.mod.json has no id.");
        modid = neoforgeModId(fabricId);
        if (!modid.equals(fabricId)) notes.add(
            "NeoForge mod ids allow only a-z, 0-9 and _, so " + fabricId + " is " + modid + " on NeoForge; " +
            "its resources and registry ids keep the " + fabricId + " namespace, and neoforge.mods.toml keeps the Fabric id for the way back."
        );
        rewriteSources("fabric", "neoforge");
        Map<String, Object> entrypoints = meta.get("entrypoints") instanceof Map<?, ?>
            ? Json.object(meta.get("entrypoints"))
            : Map.of();
        String datagen = datagenToNeoForge(entrypoints, minecraft);
        String menu = null;
        for (var entry : entrypoints.entrySet()) {
            List<String> classes = new ArrayList<>();
            for (Object item : Json.array(entry.getValue())) {
                if (item instanceof String s) classes.add(s);
                else if (item instanceof Map<?, ?>) classes.add(Json.text(Json.object(item), "value", ""));
            }
            for (String name : classes) {
                if (name.contains("::")) {
                    leave(
                        metaFile,
                        0,
                        "entrypoint " + entry.getKey() + ": " + name,
                        "a method or field entrypoint has no NeoForge counterpart; make it a class"
                    );
                    continue;
                }
                switch (entry.getKey()) {
                    case "main" -> entryToNeoForge(name, "ModInitializer", "onInitialize", null, datagen);
                    case "client" -> entryToNeoForge(
                        name,
                        "ClientModInitializer",
                        "onInitializeClient",
                        "CLIENT",
                        null
                    );
                    case "server" -> entryToNeoForge(
                        name,
                        "DedicatedServerModInitializer",
                        "onInitializeServer",
                        "DEDICATED_SERVER",
                        null
                    );
                    case "modmenu" -> menu = name;
                    case "fabric-datagen" -> {
                        if (datagen == null) leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "Fabric's data generation is converted for Minecraft 1.21.4 to 26.2 (the PackOutput providers); this one was not"
                        );
                        else if (!entrypoints.containsKey("main")) leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "NeoForge runs data generation from the mod's @Mod class, and this mod has no main entrypoint to become one"
                        );
                    }
                    default -> leave(
                        metaFile,
                        0,
                        "entrypoint " + entry.getKey() + ": " + name,
                        "an entrypoint another mod reads; NeoForge has no counterpart"
                    );
                }
            }
        }
        if (menu != null) menuToNeoForge(menu, entrypoints);
        if (!accessTransformers.isEmpty()) {
            // NeoForge applies a mod's access transformers when its mods.toml names the file (modsToml adds that).
            String existing = read(AT_FILE);
            StringBuilder lines = new StringBuilder(
                existing.isEmpty() || existing.endsWith("\n") ? existing : existing + "\n"
            );
            for (String line : accessTransformers) if (!existing.contains(line)) lines.append(line).append('\n');
            write(AT_FILE, lines.toString());
            notes.add(
                "Access transformer " +
                AT_FILE +
                " opens " +
                accessTransformers.size() +
                " member(s) the converted code calls."
            );
        }
        write("src/main/resources/META-INF/neoforge.mods.toml", modsToml(meta, minecraft));
        delete(metaFile);
        buildToNeoForge(minecraft);
        leftovers(List.of("net.fabricmc.", "com.terraformersmc.modmenu."));
    }

    /** A static field whose initializer registers something: Registry.register, buildAndRegister, registerBlock ... */
    static final Pattern STATIC_REGISTRATION = Pattern.compile(
        "(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)?static\\s+(?:final\\s+)?[^;=(){}]+=[^;]*?\\b\\w*[Rr]egister\\w*\\s*\\("
    );

    /** The file of the NeoForge @Mod class each Fabric entrypoint became, by its Fabric interface. */
    final Map<String, String> modClasses = new HashMap<>();

    static final Pattern CLASS = Pattern.compile(
        "(?m)^([ \\t]*)((?:public\\s+|final\\s+|abstract\\s+)*)class\\s+(\\w+)([^{]*)\\{"
    );

    /** Makes a Fabric entrypoint class a NeoForge @Mod class whose constructor runs the entrypoint where NeoForge lets mods register. */
    void entryToNeoForge(String name, String api, String method, String dist, String extra) throws Exception {
        String file = classFile(name);
        if (file == null) {
            leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint " + name,
                "its source file is not in this project"
            );
            return;
        }
        String text = read(file), simple = name.substring(name.lastIndexOf('.') + 1);
        unit = LoaderRules.unit(text);
        Matcher c = CLASS.matcher(text);
        boolean found = false;
        while (c.find()) if (c.group(3).equals(simple)) {
            found = true;
            break;
        }
        if (!found) {
            leave(file, 0, "entrypoint " + name, "the class declaration was not found");
            return;
        }
        if (text.contains("@Mod(")) {
            leave(
                file,
                LoaderRules.line(text, c.start()),
                "entrypoint " + name,
                "this class is already a NeoForge @Mod class, so it cannot also take " +
                api +
                "; give each entrypoint its own class"
            );
            return;
        }
        String heritage = c.group(4);
        String cleaned = heritage
            .replaceAll("\\bimplements\\s+" + api + "\\s*$", "")
            .replaceAll("\\bimplements\\s+" + api + "\\s*,\\s*", "implements ")
            .replaceAll(",\\s*" + api + "\\b", "");
        if (cleaned.equals(heritage)) {
            leave(file, LoaderRules.line(text, c.start()), "entrypoint " + name, "it does not implement " + api);
            return;
        }
        String indent = c.group(1);
        String inner = indent + unit;
        String annotation = dist == null
            ? "@Mod(" + LoaderRules.quote(modid) + ")"
            : "@Mod(value = " + LoaderRules.quote(modid) + ", dist = Dist." + dist + ")";
        // NeoForge loads a @Mod class before any registry is open. An entry class that registers in a static field
        // (a game rule, a block) must not be that class: a small generated holder runs it once registries are open.
        boolean holder = STATIC_REGISTRATION.matcher(text).find();
        String modClass = holder ? simple + "NeoForge" : simple, call = holder
            ? "new " + simple + "()." + method + "()"
            : method + "()";
        if (holder) inner = unit;
        boolean noArgs = !holder && Pattern.compile("\\b" + simple + "\\s*\\(\\s*\\)\\s*\\{").matcher(text).find();
        StringBuilder ctor = new StringBuilder();
        boolean client = "CLIENT".equals(dist);
        // Fabric runs main entrypoints where mods register, and client ones once Minecraft exists, before its options load.
        // On NeoForge those are the first RegisterEvent and the first client event, RegisterKeyMappingsEvent (posted one
        // priority phase at a time, so key mappings the entrypoint adds at normal priority are still registered).
        ctor
            .append('\n')
            .append(inner)
            .append(
                client
                    ? "/** Runs the Fabric client entrypoint at NeoForge's first client event, once Minecraft exists and before its options load. */\n"
                    : "/** Runs the Fabric entrypoint where NeoForge lets a mod register: inside its first registry event. */\n"
            );
        ctor
            .append(inner)
            .append("public ")
            .append(modClass)
            .append("(IEventBus modBus")
            .append(dist == null ? "" : ", ModContainer container")
            .append(") {\n");
        if (noArgs) ctor.append(inner).append(unit).append("this();\n");
        if (extra != null) ctor.append(inner).append(unit).append(extra).append('\n');
        if (client) ctor
            .append(inner)
            .append(unit)
            .append("modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> ")
            .append(call)
            .append(");\n");
        else {
            ctor
                .append(inner)
                .append(unit)
                .append("modBus.addListener(")
                .append(dist == null ? "" : "EventPriority.LOW, ")
                .append("RegisterEvent.class, event -> {\n");
            ctor
                .append(inner)
                .append(unit)
                .append(unit)
                .append("if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) ")
                .append(call)
                .append(";\n");
            ctor.append(inner).append(unit).append("});\n");
        }
        ctor.append(inner).append("}\n");
        String declaration =
            c.group(1) + c.group(2) + "class " + simple + (cleaned.isBlank() ? " " : " " + cleaned.strip() + " ") + "{";
        String out =
            text.substring(0, c.start()) +
            (holder ? "" : indent + annotation + "\n") +
            declaration +
            text.substring(c.end());
        out = out.replaceAll(
            "(?m)^([ \\t]*)@Override[ \\t]*\\n[ \\t]*(public\\s+void\\s+" + method + "\\s*\\()",
            "$1$2"
        );
        out = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.api\\." + api + "\\s*;[^\\n]*\\n?")
            .matcher(out)
            .replaceAll("");
        List<String> needed = new ArrayList<>(
            List.of("net.neoforged.fml.common.Mod", "net.neoforged.bus.api.IEventBus")
        );
        if (client) needed.add("net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent");
        else needed.addAll(
            List.of("net.neoforged.neoforge.registries.RegisterEvent", "net.minecraft.core.registries.Registries")
        );
        if (dist != null) needed.addAll(
            List.of(
                "net.neoforged.api.distmarker.Dist",
                "net.neoforged.fml.ModContainer",
                "net.neoforged.bus.api.EventPriority"
            )
        );
        if (extra != null && extra.contains("GatherDataEvent")) needed.add(
            "net.neoforged.neoforge.data.event.GatherDataEvent"
        );
        if (holder) {
            write(file, LoaderRules.tidy(out));
            Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            String generated =
                (pkg.find() ? "package " + pkg.group(1) + ";\n\n" : "") +
                "/** NeoForge's entry for " +
                simple +
                ", which registers in its static fields: it may load only once NeoForge opens the registries. */\n" +
                annotation +
                "\npublic final class " +
                modClass +
                " {\n" +
                ctor.substring(1) +
                "}\n";
            for (String name2 : needed) generated = LoaderRules.addImport(generated, name2);
            String holderFile = file.substring(0, file.lastIndexOf('/') + 1) + modClass + ".java";
            write(holderFile, generated);
            modClasses.put(api, holderFile);
            notes.add(
                simple +
                " registers in its static fields, so its NeoForge @Mod class is the generated " +
                modClass +
                ", which runs " +
                simple +
                "." +
                method +
                "() once the registries are open."
            );
            applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(holderFile);
            return;
        }
        // The constructor goes just before the entry method it runs, with its own blank line.
        Matcher entry = Pattern.compile(
            "(?m)^[ \\t]*(?:/\\*\\*(?:[^*]|\\*(?!/))*\\*/\\s*)?public\\s+void\\s+" + method + "\\s*\\("
        ).matcher(out);
        if (entry.find()) out =
            out.substring(0, entry.start()) + ctor.substring(1) + "\n" + out.substring(entry.start());
        else {
            int open = out.indexOf('{', out.indexOf(annotation) + annotation.length()) + 1;
            out = out.substring(0, open) + ctor + out.substring(open);
        }
        for (String name2 : needed) out = LoaderRules.addImport(out, name2);
        write(file, out);
        modClasses.put(api, file);
        applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
    }

    /** Where Fabric data generation writes: the datagen run's fabric-api.datagen.output-dir, else Loom's src/main/generated. */
    String datagenOutput = null;

    /**
     * Fabric data generation onto NeoForge's: each provider class onto the vanilla provider it wraps, the
     * fabric-datagen entrypoint onto a GatherDataEvent.Client listener the main @Mod class adds. Returns that
     * listener's line for the main constructor, or null when the mod has no datagen or it cannot be converted.
     */
    String datagenToNeoForge(Map<String, Object> entrypoints, String minecraft) throws Exception {
        List<String> names = new ArrayList<>();
        for (Object item : Json.array(entrypoints.getOrDefault("fabric-datagen", List.of()))) names.add(
            item instanceof String s ? s : Json.text(Json.object(item), "value", "")
        );
        if (names.size() != 1 || !entrypoints.containsKey("main")) return null;
        // The providers' PackOutput shape is Minecraft's from 1.21.4 to 26.2; 26.3 changed the vanilla provider constructors.
        if (Versions.compare(minecraft, "1.21.4") < 0 || Versions.compare(minecraft, "26.3") >= 0) return null;
        Map<String, String> kinds = new HashMap<>();
        boolean complete = true;
        for (String file : javaFiles()) {
            LoaderRules.Provider provider = LoaderRules.datagenProvider(read(file), modid);
            if (provider == null) continue;
            if (provider.kind().isEmpty()) {
                leave(
                    file,
                    0,
                    "data provider " + file.substring(file.lastIndexOf('/') + 1),
                    "it extends a Fabric datagen base with no rule yet (models, block loot, recipes and advancements have one)"
                );
                complete = false;
                continue;
            }
            write(file, provider.text());
            kinds.put(file.substring(file.lastIndexOf('/') + 1).replace(".java", ""), provider.kind());
            applied.computeIfAbsent("datagen-" + provider.kind(), k -> new ArrayList<>()).add(file);
        }
        String file = classFile(names.get(0));
        if (file == null) return null;
        List<String> unknown = new ArrayList<>();
        String entry = LoaderRules.datagenEntry(read(file), kinds, unknown);
        if (entry == null) {
            leave(file, 0, "entrypoint fabric-datagen", "onInitializeDataGenerator(FabricDataGenerator) was not found");
            return null;
        }
        for (String provider : unknown) leave(
            file,
            0,
            "data provider " + provider,
            "its kind has no rule, so it is not registered with GatherDataEvent"
        );
        write(file, entry);
        applied.computeIfAbsent("datagen-entrypoint", k -> new ArrayList<>()).add(file);
        datagenOutput = "src/main/generated";
        if (complete && unknown.isEmpty()) notes.add(
            "Data generation: modaccel compile --task :runClientData writes the generated files to the folder the Fabric datagen used."
        );
        return (
            "modBus.addListener(GatherDataEvent.Client.class, event -> new " +
            names.get(0) +
            "().onInitializeDataGenerator(event));"
        );
    }

    /** Mod Menu's config screen becomes NeoForge's IConfigScreenFactory, registered by the client entrypoint. */
    void menuToNeoForge(String menu, Map<String, Object> entrypoints) throws Exception {
        String file = classFile(menu);
        if (file == null) {
            leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint modmenu: " + menu,
                "its source file is not in this project"
            );
            return;
        }
        String text = read(file);
        if (
            Pattern.compile("getProvidedConfigScreenFactories|getUpdateChecker|attachModpackBadges")
                .matcher(text)
                .find()
        ) leave(
            file,
            0,
            "Mod Menu API",
            "only getModConfigScreenFactory has a NeoForge counterpart (IConfigScreenFactory)"
        );
        write(file, LoaderRules.modMenuToNeoForge(text));
        for (String other : javaFiles()) {
            String body = read(other);
            String next = body.replace(".getModConfigScreenFactory().create(", ".getModConfigScreenFactory().apply(");
            write(other, next);
        }
        String register =
            "container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new " +
            menu +
            "().getModConfigScreenFactory().apply(parent));";
        String client = null;
        for (Object item : Json.array(entrypoints.getOrDefault("client", List.of()))) client = item instanceof String s
            ? s
            : Json.text(Json.object(item), "value", "");
        String clientFile = client == null ? null : modClasses.getOrDefault("ClientModInitializer", classFile(client));
        if (clientFile == null) {
            leave(
                file,
                0,
                "Mod Menu entry " + menu,
                "the mod has no client entrypoint to register NeoForge's config screen from"
            );
            return;
        }
        String text2 = read(clientFile);
        Matcher ctor = Pattern.compile("ModContainer container\\) \\{\\n").matcher(text2);
        if (!ctor.find()) {
            leave(
                clientFile,
                0,
                "Mod Menu entry " + menu,
                "the client entrypoint was not converted, so the config screen is not registered"
            );
            return;
        }
        int at = ctor.end();
        text2 = text2.substring(0, at) + LoaderRules.indent(text2, at) + register + "\n" + text2.substring(at);
        text2 = LoaderRules.addImport(text2, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
        write(clientFile, text2);
        applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
        notes.add(
            "Mod Menu's config screen opens from NeoForge's own mod list (IConfigScreenFactory); the modmenu dependency is gone."
        );
    }

    static String tomlString(String value) {
        if (value.contains("\n")) return "'''\n" + value + "\n'''";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A Fabric version predicate as a Maven range: >=1.2 is [1.2,), ~26.3 is [26.3,26.4), * is any. */
    static String mavenRange(Object predicate) {
        String value = predicate instanceof List<?> list && !list.isEmpty()
            ? String.valueOf(list.get(0))
            : String.valueOf(predicate);
        value = value.strip();
        if (value.equals("*") || value.isEmpty()) return "[0,)";
        Matcher tilde = Pattern.compile("^~(\\d+)\\.(\\d+)(.*)$").matcher(value);
        if (tilde.find()) return (
            "[" + value.substring(1) + "," + tilde.group(1) + "." + (Integer.parseInt(tilde.group(2)) + 1) + ")"
        );
        Matcher caret = Pattern.compile("^\\^(\\d+)(.*)$").matcher(value);
        if (caret.find()) return "[" + value.substring(1) + "," + (Integer.parseInt(caret.group(1)) + 1) + ")";
        String low = null, high = null;
        boolean lowIn = true, highIn = false;
        for (String part : value.split("\\s+")) {
            if (part.startsWith(">=")) low = part.substring(2);
            else if (part.startsWith(">")) {
                low = part.substring(1);
                lowIn = false;
            } else if (part.startsWith("<=")) {
                high = part.substring(2);
                highIn = true;
            } else if (part.startsWith("<")) high = part.substring(1);
            else if (part.startsWith("=")) return "[" + part.substring(1) + "]";
            else return "[" + part + "]";
        }
        return (
            (low == null ? "(" : lowIn ? "[" : "(") +
            (low == null ? "" : low) +
            "," +
            (high == null ? "" : high) +
            (high == null ? ")" : highIn ? "]" : ")")
        );
    }

    /** A Maven range as a Fabric version predicate. */
    static String fabricPredicate(String range) {
        String value = range.strip();
        if (value.equals("[0,)") || value.equals("*") || value.isEmpty()) return "*";
        Matcher m = Pattern.compile("^([\\[(])([^,]*)(?:,([^\\])]*))?([\\])])$").matcher(value);
        if (!m.find()) return value;
        if (m.group(3) == null) return m.group(2);
        Matcher minor = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.0)?$").matcher(m.group(2));
        if (
            m.group(1).equals("[") &&
            m.group(4).equals(")") &&
            minor.find() &&
            m.group(3).equals(minor.group(1) + "." + (Integer.parseInt(minor.group(2)) + 1))
        ) return "~" + m.group(2);
        List<String> parts = new ArrayList<>();
        if (!m.group(2).isEmpty()) parts.add((m.group(1).equals("[") ? ">=" : ">") + m.group(2));
        if (!m.group(3).isEmpty()) parts.add((m.group(4).equals("]") ? "<=" : "<") + m.group(3));
        return parts.isEmpty() ? "*" : String.join(" ", parts);
    }

    static final Set<String> FABRIC_ONLY = Set.of(
        "fabricloader",
        "fabric",
        "fabric-api",
        "fabric-api-base",
        "java",
        "modmenu"
    );

    String modsToml(Map<String, Object> meta, String minecraft) {
        StringBuilder out = new StringBuilder();
        out.append("modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\n");
        Object license = meta.get("license");
        String licence = license instanceof List<?> l
            ? String.join(", ", l.stream().map(String::valueOf).toList())
            : license == null ? "All Rights Reserved" : String.valueOf(license);
        out.append("license = ").append(tomlString(licence)).append('\n');
        Map<String, Object> contact = meta.get("contact") instanceof Map<?, ?>
            ? Json.object(meta.get("contact"))
            : Map.of();
        if (contact.get("issues") != null) out
            .append("issueTrackerURL = ")
            .append(tomlString(String.valueOf(contact.get("issues"))))
            .append('\n');
        out.append("\n[[mods]]\nmodId = ").append(tomlString(modid)).append('\n');
        out.append("version = ").append(tomlString(Json.text(meta, "version", "${version}"))).append('\n');
        out.append("displayName = ").append(tomlString(Json.text(meta, "name", modid))).append('\n');
        List<String> authors = new ArrayList<>();
        for (Object a : meta.get("authors") instanceof List<?>
            ? Json.array(meta.get("authors"))
            : List.of()) authors.add(
            a instanceof Map<?, ?> ? Json.text(Json.object(a), "name", "") : String.valueOf(a)
        );
        if (!authors.isEmpty()) out.append("authors = ").append(tomlString(String.join(", ", authors))).append('\n');
        if (contact.get("homepage") != null) out
            .append("displayURL = ")
            .append(tomlString(String.valueOf(contact.get("homepage"))))
            .append('\n');
        Object icon = meta.get("icon");
        // NeoForge 26.2 reads a square iconFile and warns about logoFile; earlier releases read only logoFile.
        String iconKey = Versions.compare(minecraft, "26.2") >= 0 ? "iconFile" : "logoFile";
        if (icon instanceof String s) out.append(iconKey + " = ").append(tomlString(s)).append('\n');
        else if (icon instanceof Map<?, ?> sizes && !sizes.isEmpty()) out
            .append(iconKey + " = ")
            .append(tomlString(String.valueOf(new TreeMap<>(Json.object(icon)).lastEntry().getValue())))
            .append('\n');
        out.append("description = ").append(tomlString(Json.text(meta, "description", ""))).append('\n');
        if (!modid.equals(fabricId)) out.append("\n[modproperties.").append(modid).append("]\nfabricId = ").append(tomlString(fabricId)).append('\n');
        if ("client".equals(meta.get("environment"))) notes.add(
            "fabric.mod.json said environment client: on NeoForge the mod's only entry class is @Mod(dist = Dist.CLIENT), so a server loads none of its code."
        );
        if ("server".equals(meta.get("environment"))) notes.add(
            "fabric.mod.json said environment server: on NeoForge the mod's only entry class is @Mod(dist = Dist.DEDICATED_SERVER)."
        );
        String neoRange = "[" + neoforgePrefix(minecraft).replaceAll("\\.$", "") + ",)";
        out
            .append("\n[[dependencies.")
            .append(modid)
            .append("]]\nmodId = \"neoforge\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString(neoRange))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("depends", "required");
        kinds.put("recommends", "optional");
        kinds.put("suggests", "optional");
        kinds.put("breaks", "incompatible");
        kinds.put("conflicts", "discouraged");
        boolean minecraftDone = false;
        for (var kind : kinds.entrySet()) {
            if (!(meta.get(kind.getKey()) instanceof Map<?, ?>)) continue;
            for (var dep : Json.object(meta.get(kind.getKey())).entrySet()) {
                String id = dep.getKey();
                if (FABRIC_ONLY.contains(id)) continue;
                if (id.equals("another-mod")) continue;
                String range = mavenRange(dep.getValue());
                if (id.equals("minecraft")) {
                    minecraftDone = true;
                    if (range.equals("[0,)")) range = "[" + minecraft + ",)";
                }
                out
                    .append("\n[[dependencies.")
                    .append(modid)
                    .append("]]\nmodId = ")
                    .append(tomlString(id))
                    .append("\ntype = ")
                    .append(tomlString(kind.getValue()))
                    .append("\nversionRange = ")
                    .append(tomlString(range))
                    .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
                if (!id.equals("minecraft")) notes.add(
                    "Dependency " +
                    id +
                    " (" +
                    kind.getKey() +
                    ") is kept by id; it needs a NeoForge build of that mod."
                );
            }
        }
        if (!minecraftDone) out
            .append("\n[[dependencies.")
            .append(modid)
            .append("]]\nmodId = \"minecraft\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString("[" + minecraft + ",)"))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        if (!accessTransformers.isEmpty()) out.append(
            "\n[[accessTransformers]]\nfile = \"META-INF/accesstransformer.cfg\"\n"
        );
        for (Object m : meta.get("mixins") instanceof List<?> ? Json.array(meta.get("mixins")) : List.of()) {
            String config = m instanceof Map<?, ?> ? Json.text(Json.object(m), "config", "") : String.valueOf(m);
            out.append("\n[[mixins]]\nconfig = ").append(tomlString(config)).append('\n');
        }
        if (meta.get("accessWidener") != null) leave(
            "src/main/resources/fabric.mod.json",
            0,
            "accessWidener " + meta.get("accessWidener"),
            "access wideners are not converted to NeoForge access transformers yet"
        );
        if (meta.get("jars") != null) leave(
            "src/main/resources/fabric.mod.json",
            0,
            "jars",
            "nested jars are declared by the build (jarJar) on NeoForge; convert them by hand"
        );
        return out.toString();
    }

    String neoforgePrefix(String minecraft) {
        String[] parts = minecraft.split("\\.");
        if (parts[0].equals("1")) return (
            (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + "."
        );
        return parts[0] + "." + (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + ".";
    }

    static List<String> metadataVersions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1).strip());
        return out;
    }

    static String newest(List<String> versions) {
        String best = null;
        for (String v : versions) if (best == null || Versions.compare(v, best) > 0) best = v;
        return best;
    }

    String latestNeoForge(String minecraft) throws Exception {
        String prefix = neoforgePrefix(minecraft);
        List<String> found = new ArrayList<>();
        for (String v : metadataVersions(
            Target.fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        )) if (v.startsWith(prefix) && !v.contains("-")) found.add(v);
        if (found.isEmpty()) throw new IOException(
            "NeoForge has published no release for Minecraft " +
            minecraft +
            " (no net.neoforged:neoforge " +
            prefix +
            "x on maven.neoforged.net)."
        );
        return newest(found);
    }

    /**
     * The newest stable release of a Gradle plugin that runs on this project's Gradle: its published Gradle module
     * metadata names the oldest Gradle it supports (org.gradle.plugin.api-version), and alphas and snapshots are skipped.
     */
    String stablePlugin(String repository, String group, String artifact) throws Exception {
        String base = repository + "/" + group.replace('.', '/') + "/" + artifact;
        List<String> stable = new ArrayList<>(
            metadataVersions(Target.fetch(build, base + "/maven-metadata.xml"))
                .stream()
                .filter(v -> !v.contains("-"))
                .toList()
        );
        stable.sort((a, b) -> Versions.compare(b, a));
        String gradle = project.wrapperVersion();
        for (String version : stable.subList(0, Math.min(12, stable.size()))) {
            String needs = null;
            try {
                for (Object variant : Json.array(
                    Json.object(
                        Json.parse(
                            Target.fetch(build, base + "/" + version + "/" + artifact + "-" + version + ".module")
                        )
                    ).get("variants")
                )) {
                    Object attributes = Json.object(variant).get("attributes");
                    if (
                        attributes instanceof Map<?, ?> &&
                        Json.object(attributes).get("org.gradle.plugin.api-version") != null
                    ) needs = String.valueOf(Json.object(attributes).get("org.gradle.plugin.api-version"));
                }
            } catch (IOException | IllegalArgumentException noModule) {
                // No Gradle module metadata: nothing says which Gradle it needs.
            }
            if (needs == null || gradle == null || Versions.compare(gradle, needs) >= 0) return version;
        }
        throw new IOException(
            "No stable " +
            group +
            ":" +
            artifact +
            " release in " +
            repository +
            " runs on this project's Gradle " +
            gradle +
            "."
        );
    }

    static Map<String, String> properties(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            String s = line.strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            out.put(s.substring(0, s.indexOf('=')).strip(), s.substring(s.indexOf('=') + 1).strip());
        }
        return out;
    }

    /** Sets, adds or removes (value null) gradle.properties keys, keeping comments and order. */
    static String setProperties(String text, Map<String, String> values) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            String key = s.substring(0, s.indexOf('=')).strip();
            if (!values.containsKey(key)) continue;
            done.add(key);
            if (values.get(key) == null) lines.remove(i--);
            else lines.set(i, key + "=" + values.get(key));
        }
        for (var entry : values.entrySet()) if (entry.getValue() != null && !done.contains(entry.getKey())) lines.add(
            entry.getKey() + "=" + entry.getValue()
        );
        return String.join("\n", lines) + "\n";
    }

    /** The span of a top-level block "name {" ... "}" in a build script, or null. */
    static int[] block(String text, String name) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(name) + "\\s*\\{").matcher(text);
        while (m.find()) {
            if (!code[m.start() + m.group().indexOf(name.charAt(0))]) continue;
            int open = m.end() - 1, end = LoaderRules.close(text, code, open);
            if (end < 0) return null;
            int stop = end;
            while (stop < text.length() && text.charAt(stop) != '\n') stop++;
            return new int[] { m.start(), Math.min(text.length(), stop + 1), open, end };
        }
        return null;
    }

    /** The named blocks directly inside a block body: runs { client { ... } } gives client. */
    static Map<String, String> children(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean[] code = LoaderRules.code(body);
        Matcher m = Pattern.compile("([\\w\"'${}]+)\\s*\\{").matcher(body);
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1, end = LoaderRules.close(body, code, open);
            if (end < 0) break;
            out.put(m.group(1).replaceAll("[\"']", ""), body.substring(open + 1, end - 1));
            from = end;
        }
        return out;
    }

    String runToNeoForge(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = unit + unit + unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher arg = Pattern.compile("^(?:vmArg|jvmArg)\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            Matcher prop = Pattern.compile(
                "^property\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\s*\\)?$"
            ).matcher(line);
            Matcher dir = Pattern.compile("^runDir\\s*=?\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?$").matcher(line);
            Matcher program = Pattern.compile("^programArgs?\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.matches("(?:name|configName)\\s*=?\\s*\\(?\\s*[\"'].*")) out
                .append(in)
                .append("ideName = ")
                .append(line.replaceFirst("^(?:name|configName)\\s*=?\\s*\\(?\\s*", "").replaceFirst("\\)$", ""))
                .append('\n');
            else if (line.matches("ideConfigGenerated\\b.*")) continue;
            else if (arg.find()) {
                Matcher d = Pattern.compile("^-D([^=]+)=(.*)$").matcher(arg.group(1));
                if (d.find()) out
                    .append(in)
                    .append("systemProperty '")
                    .append(d.group(1))
                    .append("', '")
                    .append(d.group(2))
                    .append("'\n");
                else out.append(in).append("jvmArgument '").append(arg.group(1)).append("'\n");
            } else if (prop.find()) out
                .append(in)
                .append("systemProperty '")
                .append(prop.group(1))
                .append("', '")
                .append(prop.group(2))
                .append("'\n");
            else if (dir.find()) out
                .append(in)
                .append("gameDirectory = project.file('")
                .append(dir.group(1))
                .append("')\n");
            else if (program.find()) out.append(in).append("programArgument '").append(program.group(1)).append("'\n");
            else leave(
                "build.gradle",
                0,
                "loom run " + name + ": " + line,
                "this Loom run setting has no ModDevGradle counterpart in the rules"
            );
        }
        return out.toString();
    }

    static final Pattern LOOM_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"](?:net\\.fabricmc\\.)?fabric-loom['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    void buildToNeoForge(String minecraft) throws Exception {
        String gradle = read("build.gradle");
        Matcher plugin = LOOM_PLUGIN.matcher(gradle);
        if (!plugin.find()) throw new IOException(
            "build.gradle does not apply the Fabric Loom plugin with id 'fabric-loom' or 'net.fabricmc.fabric-loom' in its plugins block."
        );
        String neo = latestNeoForge(minecraft);
        String mdg = stablePlugin("https://maven.neoforged.net/releases", "net.neoforged", "moddev-gradle");
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            "id 'net.neoforged.moddev' version '" +
            mdg +
            "'" +
            gradle.substring(plugin.end());
        boolean split = false;
        StringBuilder runs = new StringBuilder();
        runs
            .append(unit)
            .append(unit)
            .append("client {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("client()\n")
            .append(unit)
            .append(unit)
            .append("}\n");
        runs
            .append(unit)
            .append(unit)
            .append("server {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("server()\n")
            .append(unit)
            .append(unit)
            .append("}\n");
        int[] loom = block(gradle, "loom");
        int insertAt;
        if (loom != null) {
            String body = gradle.substring(loom[2] + 1, loom[3] - 1);
            Map<String, String> parts = children(body);
            for (var part : parts.entrySet()) {
                if (part.getKey().equals("runs")) {
                    for (var run : children(part.getValue()).entrySet()) {
                        if (run.getValue().contains("fabric-api.datagen")) {
                            Matcher dir = Pattern.compile("fabric-api\\.datagen\\.output-dir=([^\"'\\s]+)").matcher(
                                run.getValue()
                            );
                            if (datagenOutput != null && dir.find()) datagenOutput = dir
                                .group(1)
                                .replaceFirst("^\\$\\{(?:project\\.)?projectDir}/", "");
                            if (datagenOutput == null) leave(
                                "build.gradle",
                                0,
                                "loom run " + run.getKey(),
                                "it runs Fabric data generation, which was not converted"
                            );
                            continue;
                        }
                        if (Set.of("client", "server").contains(run.getKey())) continue;
                        runs
                            .append(unit)
                            .append(unit)
                            .append(run.getKey())
                            .append(" {\n")
                            .append(runToNeoForge(run.getKey(), run.getValue()))
                            .append(unit)
                            .append(unit)
                            .append("}\n");
                    }
                } else if (!part.getKey().equals("mods")) leave(
                    "build.gradle",
                    0,
                    "loom " + part.getKey() + " block",
                    "no ModDevGradle counterpart in the rules"
                );
            }
            String rest = body;
            for (var part : parts.entrySet()) rest = rest.replace(part.getValue(), "");
            for (String raw : rest.lines().toList()) {
                String line = raw.strip().replaceAll("[\\w\"'${}]+\\s*\\{\\s*\\}", "").strip();
                if (line.isEmpty() || line.startsWith("//") || line.equals("}")) continue;
                if (line.startsWith("splitEnvironmentSourceSets")) split = true;
                else if (line.startsWith("accessWidenerPath")) leave(
                    "build.gradle",
                    0,
                    line,
                    "access wideners are not converted to NeoForge access transformers yet"
                );
                else leave("build.gradle", 0, "loom: " + line, "no ModDevGradle counterpart in the rules");
            }
            gradle = gradle.substring(0, loom[0]) + gradle.substring(loom[1]);
            insertAt = loom[0];
        } else {
            int[] deps = block(gradle, "dependencies");
            insertAt = deps == null ? gradle.length() : deps[0];
        }
        int[] fabricApi = block(gradle, "fabricApi");
        if (fabricApi != null) {
            if (gradle.substring(fabricApi[0], fabricApi[1]).contains("DataGeneration") && datagenOutput == null) leave(
                "build.gradle",
                0,
                "fabricApi { configureDataGeneration }",
                "Fabric data generation was not converted"
            );
            if (fabricApi[0] < insertAt) insertAt -= fabricApi[1] - fabricApi[0];
            gradle = gradle.substring(0, fabricApi[0]) + gradle.substring(fabricApi[1]);
        }
        if (datagenOutput != null) {
            runs
                .append(unit)
                .append(unit)
                .append("clientData {\n")
                .append(unit)
                .append(unit)
                .append(unit)
                .append("clientData()\n");
            runs
                .append(unit)
                .append(unit)
                .append(unit)
                .append("programArguments.addAll('--mod', '")
                .append(modid)
                .append("', '--all', '--output', file('")
                .append(datagenOutput)
                .append("').absolutePath, '--existing', file('src/main/resources').absolutePath)\n");
            runs.append(unit).append(unit).append("}\n");
        }
        StringBuilder neoForge = new StringBuilder("neoForge {\n");
        neoForge
            .append(unit)
            .append("version = project.neo_version\n\n")
            .append(unit)
            .append("runs {\n")
            .append(runs)
            .append(unit)
            .append("}\n\n");
        neoForge
            .append(unit)
            .append("mods {\n")
            .append(unit)
            .append(unit)
            .append(LoaderRules.quote(modid))
            .append(" {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("sourceSet sourceSets.main\n")
            .append(unit)
            .append(unit)
            .append("}\n")
            .append(unit)
            .append("}\n}\n");
        if (datagenOutput != null) neoForge
            .append(
                "\n// Generated data (runClientData) ships with the mod's own resources.\nsourceSets.main.resources {\n"
            )
            .append(unit)
            .append("srcDir '")
            .append(datagenOutput)
            .append("'\n")
            .append(unit)
            .append("exclude '.cache'\n}\n");
        if (split) neoForge.append(
            "\n// NeoForge has no split client source set: the client sources build with the rest.\nsourceSets.main.java.srcDir 'src/client/java'\nsourceSets.main.resources.srcDir 'src/client/resources'\n"
        );
        gradle =
            gradle.substring(0, insertAt) +
            neoForge +
            (insertAt < gradle.length() && gradle.charAt(insertAt) != '\n' ? "\n" : "") +
            gradle.substring(insertAt);
        int[] deps = block(gradle, "dependencies");
        if (deps != null) {
            List<String> kept = new ArrayList<>();
            boolean dropping = false;
            for (String raw : gradle.substring(deps[2] + 1, deps[3] - 1).lines().toList()) {
                String line = raw.strip();
                boolean drop =
                    line.matches("(?:minecraft|mappings)\\b.*") ||
                    line.contains("net.fabricmc:fabric-loader") ||
                    line.contains("net.fabricmc.fabric-api") ||
                    line.contains("fabricApi.module") ||
                    line.contains("com.terraformersmc:modmenu");
                if (drop) {
                    // A comment that only introduced the dropped lines goes with them.
                    while (
                        !dropping &&
                        kept.size() >= 2 &&
                        kept.get(kept.size() - 1).strip().startsWith("//") &&
                        kept.get(kept.size() - 2).isBlank()
                    ) kept.remove(kept.size() - 1);
                    dropping = true;
                    continue;
                }
                dropping = false;
                Matcher mod = Pattern.compile(
                    "^(mod(?:Implementation|Api|CompileOnly|CompileOnlyApi|RuntimeOnly|LocalRuntime)|include|localRuntime)\\b"
                ).matcher(line);
                if (mod.find()) {
                    String config =
                        switch (mod.group(1)) {
                            case "modImplementation" -> "implementation";
                            case "modApi" -> "api";
                            case "modCompileOnly", "modCompileOnlyApi" -> "compileOnly";
                            case "modRuntimeOnly", "modLocalRuntime", "localRuntime" -> "runtimeOnly";
                            default -> "jarJar";
                        };
                    leave(
                        "build.gradle",
                        0,
                        "dependency " + line,
                        "another mod or library on the Fabric classpath: it needs its NeoForge build, which convert cannot choose"
                    );
                    raw = raw.replaceFirst(Pattern.quote(mod.group(1)), config);
                }
                kept.add(raw);
            }
            String inner = String.join("\n", kept) + "\n";
            gradle =
                gradle.substring(0, deps[2] + 1) + "\n" + inner.replaceAll("^\\n+", "") + gradle.substring(deps[3] - 1);
        }
        gradle = gradle
            .replace("filesMatching(\"fabric.mod.json\")", "filesMatching(\"META-INF/neoforge.mods.toml\")")
            .replace("filesMatching('fabric.mod.json')", "filesMatching('META-INF/neoforge.mods.toml')");
        gradle = gradle.replaceAll("\\bremapJar\\b", "jar").replaceAll("\\bremapSourcesJar\\b", "sourcesJar");
        if (gradle.contains("modmenu") || gradle.contains("fabricmc")) for (String line : gradle.lines().toList()) if (
            line.contains("fabricmc") && !line.strip().startsWith("//")
        ) leave("build.gradle", 0, line.strip(), "still names Fabric; no rule changes it");
        write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        for (String key : List.of(
            "loader_version",
            "fabric_loader_version",
            "fabric_version",
            "fabric_api_version",
            "loom_version",
            "yarn_mappings",
            "modmenu_version"
        )) props.put(key, null);
        props.put("neo_version", neo);
        write("gradle.properties", setProperties(read("gradle.properties"), props));
        String settings = read("settings.gradle");
        if (settings.contains("pluginManagement") && !settings.contains("gradlePluginPortal()")) {
            write(
                "settings.gradle",
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)",
                    "$1\n" + unit + unit + "gradlePluginPortal()"
                )
            );
        }
        notes.add(
            "Build: ModDevGradle " +
            mdg +
            " with NeoForge " +
            neo +
            " (the newest release for Minecraft " +
            minecraft +
            ")."
        );
    }

    // ---- NeoForge to Fabric ------------------------------------------------------------------------------

    /** A small TOML reader for neoforge.mods.toml: top-level keys, [[mods]], [[dependencies.id]] and [[mixins]] tables. */
    static Map<String, Object> toml(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> current = out;
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher table = Pattern.compile("^\\[\\[([\\w.\\-]+)]]").matcher(line);
            if (table.find()) {
                current = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) out.computeIfAbsent("[[" + table.group(1) + "]]", k ->
                    new ArrayList<>()
                );
                list.add(current);
                continue;
            }
            if (line.startsWith("[")) {
                current = new LinkedHashMap<>();
                out.put(line, current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip(), value = line.substring(eq + 1).strip();
            for (String quote : List.of("'''", "\"\"\"")) if (value.startsWith(quote)) {
                StringBuilder s = new StringBuilder(value.substring(3));
                while (!s.toString().contains(quote) && i + 1 < lines.size()) s.append('\n').append(lines.get(++i));
                String all = s.toString();
                value = all.substring(0, all.indexOf(quote)).replaceAll("^\\n", "").replaceAll("\\n$", "");
                current.put(key, value);
                key = null;
                break;
            }
            if (key == null) continue;
            if (value.startsWith("\"")) {
                Matcher m = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value);
                current.put(key, m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : value);
            } else if (value.startsWith("'")) current.put(key, value.substring(1, value.indexOf('\'', 1)));
            else current.put(key, value.replaceAll("\\s*#.*$", ""));
        }
        return out;
    }

    void toFabric(String minecraft) throws Exception {
        String metaFile = "src/main/resources/META-INF/neoforge.mods.toml";
        Map<String, Object> toml = toml(read(metaFile));
        List<Object> mods = toml.get("[[mods]]") instanceof List<?> l ? Json.array(l) : List.of();
        if (mods.size() != 1) throw new IOException(
            "neoforge.mods.toml declares " + mods.size() + " mods; convert moves a jar with one mod."
        );
        Map<String, Object> mod = Json.object(mods.get(0));
        modid = Json.text(mod, "modId", "");
        fabricId = toml.get("[modproperties." + modid + "]") instanceof Map<?, ?> properties ? Json.text(Json.object(properties), "fabricId", modid) : modid;
        // The @Mod classes first: their annotation names Dist, which the source rules would otherwise rename.
        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        String menu = null;
        for (String file : javaFiles()) {
            String text = read(file);
            Matcher annotation = Pattern.compile(
                "@Mod\\(\\s*(?:value\\s*=\\s*)?([^,)]+?)\\s*(?:,\\s*dist\\s*=\\s*Dist\\.(\\w+)\\s*)?\\)"
            ).matcher(text);
            if (!annotation.find()) continue;
            String name = file.replaceFirst("^src/\\w+/java/", "").replaceAll("\\.java$", "").replace('/', '.');
            String dist = annotation.group(2);
            String api = dist == null
                ? "ModInitializer"
                : dist.equals("CLIENT") ? "ClientModInitializer" : "DedicatedServerModInitializer";
            String method = dist == null
                ? "onInitialize"
                : dist.equals("CLIENT") ? "onInitializeClient" : "onInitializeServer";
            Matcher menuLine = Pattern.compile(
                "container\\.registerExtensionPoint\\(IConfigScreenFactory\\.class, \\(modContainer, parent\\) -> new ([\\w.]+)\\(\\)\\.getModConfigScreenFactory\\(\\)\\.apply\\(parent\\)\\);\\n"
            ).matcher(text);
            if (menuLine.find()) {
                menu = menuLine.group(1);
                text = text.substring(0, text.lastIndexOf('\n', menuLine.start()) + 1) + text.substring(menuLine.end());
                text = LoaderRules.dropImport(text, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
            }
            Matcher made = Pattern.compile(
                "/\\*\\* NeoForge's entry for (\\w+), which registers in its static fields"
            ).matcher(text);
            if (made.find()) {
                String target = file.substring(0, file.lastIndexOf('/') + 1) + made.group(1) + ".java";
                if (
                    !Files.isRegularFile(project.root.resolve(target)) ||
                    !text.contains("new " + made.group(1) + "()." + method + "()")
                ) {
                    leave(
                        file,
                        0,
                        "@Mod class " + made.group(1) + "NeoForge",
                        "it names an entry class this conversion cannot find"
                    );
                    continue;
                }
                delete(file);
                write(target, implementFabric(read(target), made.group(1), api, method));
                entrypoints
                    .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                        new ArrayList<>()
                    )
                    .add(name.substring(0, name.lastIndexOf('.') + 1) + made.group(1));
                applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(target);
                continue;
            }
            String converted = entryToFabric(file, text, annotation, api, method);
            if (converted == null) continue;
            write(file, converted);
            entrypoints
                .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                    new ArrayList<>()
                )
                .add(name);
            applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
        }
        rewriteSources("neoforge", "fabric");
        if (menu != null) {
            String file = classFile(menu);
            if (file != null) {
                write(file, LoaderRules.modMenuToFabric(read(file)));
                for (String other : javaFiles()) write(
                    other,
                    read(other).replace(".getModConfigScreenFactory().apply(", ".getModConfigScreenFactory().create(")
                );
                entrypoints.computeIfAbsent("modmenu", k -> new ArrayList<>()).add(menu);
                applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
            }
        }
        buildToFabric(minecraft, menu != null);
        // Access transformer lines a rule added for NeoForge go away with the code that needed them.
        Set<String> known = new HashSet<>();
        for (Object rule : LoaderRules.list(rules.get("expressions"))) if (
            Json.object(rule).get("accessTransformers") instanceof Map<?, ?> at
        ) known.addAll(LoaderRules.strings(Json.object(at).get("neoforge")));
        boolean atLeft = false;
        if (Files.isRegularFile(project.root.resolve(AT_FILE))) {
            List<String> keep = new ArrayList<>();
            for (String line : read(AT_FILE).lines().toList()) if (!known.contains(line.strip())) keep.add(line);
            if (keep.stream().allMatch(l -> l.isBlank() || l.strip().startsWith("#"))) delete(AT_FILE);
            else {
                write(AT_FILE, String.join("\n", keep) + "\n");
                atLeft = true;
            }
        }
        if (!atLeft) toml.remove("[[accessTransformers]]");
        write("src/main/resources/fabric.mod.json", fabricJson(toml, mod, entrypoints, minecraft, menu != null));
        delete(metaFile);
        leftovers(List.of("net.neoforged."));
    }

    /** A NeoForge @Mod class back as a Fabric entrypoint: the constructor convert wrote is removed; any other constructor is named. */
    String entryToFabric(String file, String text, Matcher annotation, String api, String method) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
        Matcher ctor = Pattern.compile(
            "(?s)\\n[ \\t]*/\\*\\* Runs the Fabric (?:client )?entrypoint[^\\n]*\\n[ \\t]*public " +
            simple +
            "\\(IEventBus modBus(?:, ModContainer container)?\\) \\{\\n(.*?)\\n[ \\t]*}\\n"
        ).matcher(text);
        if (!ctor.find() || !ctor.group(1).contains(method + "()")) {
            leave(
                file,
                LoaderRules.line(text, annotation.start()),
                "@Mod class " + simple,
                "its constructor is NeoForge code convert did not write; move its work into " +
                method +
                "() of a Fabric " +
                api +
                " by hand"
            );
            return null;
        }
        if (
            ctor
                .group(1)
                .replaceAll(
                    "(?s)this\\(\\);|modBus\\.addListener\\(.*?RegisterEvent\\.class, event -> \\{.*?}\\);|modBus\\.addListener\\(EventPriority\\.HIGHEST, RegisterKeyMappingsEvent\\.class, event -> \\w+\\(\\)\\);",
                    ""
                )
                .strip()
                .length() >
            0
        ) {
            leave(
                file,
                LoaderRules.line(text, ctor.start()),
                "@Mod constructor of " + simple,
                "it does more than run " + method + "(); move that work by hand"
            );
            return null;
        }
        String out = text.substring(0, ctor.start()) + "\n" + text.substring(ctor.end());
        out = out.replace("\n\n\n", "\n\n");
        int lineStart = out.lastIndexOf('\n', out.indexOf(annotation.group())) + 1;
        int lineEnd = out.indexOf('\n', out.indexOf(annotation.group())) + 1;
        out = out.substring(0, lineStart) + out.substring(lineEnd);
        out = implementFabric(out, simple, api, method);
        for (String name : List.of(
            "net.neoforged.fml.common.Mod",
            "net.neoforged.bus.api.IEventBus",
            "net.neoforged.neoforge.registries.RegisterEvent",
            "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
            "net.minecraft.core.registries.Registries",
            "net.neoforged.api.distmarker.Dist",
            "net.neoforged.fml.ModContainer",
            "net.neoforged.bus.api.EventPriority"
        )) out = LoaderRules.dropImport(out, name);
        return out;
    }

    /** The class implements the Fabric entrypoint interface again, with @Override on its entry method. */
    static String implementFabric(String out, String simple, String api, String method) {
        Matcher c = CLASS.matcher(out);
        while (c.find()) if (c.group(3).equals(simple)) {
            String heritage = c.group(4);
            String next = heritage.contains("implements")
                ? heritage.replaceFirst("implements\\s+", "implements " + api + ", ")
                : heritage.strip().isEmpty()
                    ? " implements " + api + " "
                    : " " + heritage.strip() + " implements " + api + " ";
            out =
                out.substring(0, c.start()) +
                c.group(1) +
                c.group(2) +
                "class " +
                simple +
                next +
                "{" +
                out.substring(c.end());
            break;
        }
        out = out.replaceFirst("(?m)^([ \\t]*)(public\\s+void\\s+" + method + "\\s*\\()", "$1@Override\n$1$2");
        return LoaderRules.addImport(out, "net.fabricmc.api." + api);
    }

    String fabricJson(
        Map<String, Object> toml,
        Map<String, Object> mod,
        Map<String, List<String>> entrypoints,
        String minecraft,
        boolean menu
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("id", fabricId);
        out.put("version", Json.text(mod, "version", "${version}"));
        out.put("name", Json.text(mod, "displayName", modid));
        out.put("description", Json.text(mod, "description", ""));
        String authors = Json.text(mod, "authors", "");
        if (!authors.isEmpty()) out.put("authors", Arrays.stream(authors.split("\\s*,\\s*")).toList());
        Map<String, Object> contact = new LinkedHashMap<>();
        if (mod.get("displayURL") != null) contact.put("homepage", mod.get("displayURL"));
        if (toml.get("issueTrackerURL") != null) contact.put("issues", toml.get("issueTrackerURL"));
        if (!contact.isEmpty()) out.put("contact", contact);
        out.put("license", Json.text(toml, "license", "All Rights Reserved"));
        for (String key : List.of("iconFile", "logoFile")) if (
            mod.get(key) != null && !out.containsKey("icon")
        ) out.put("icon", mod.get(key));
        boolean client = entrypoints.containsKey("client"), main = entrypoints.containsKey("main"), server =
            entrypoints.containsKey("server");
        out.put("environment", main || (client && server) ? "*" : client ? "client" : server ? "server" : "*");
        out.put("entrypoints", entrypoints);
        List<Object> mixins = new ArrayList<>();
        for (Object m : toml.get("[[mixins]]") instanceof List<?> l ? Json.array(l) : List.of()) mixins.add(
            Json.text(Json.object(m), "config", "")
        );
        if (!mixins.isEmpty()) out.put("mixins", mixins);
        Map<String, Object> depends = new LinkedHashMap<>(), suggests = new LinkedHashMap<>(), breaks =
            new LinkedHashMap<>(), conflicts = new LinkedHashMap<>();
        depends.put("fabricloader", ">=" + fabricLoader);
        depends.put("minecraft", "~" + minecraft);
        depends.put("java", ">=" + javaVersion());
        depends.put("fabric-api", "*");
        for (Object d : toml.get("[[dependencies." + modid + "]]") instanceof List<?> l ? Json.array(l) : List.of()) {
            Map<String, Object> dep = Json.object(d);
            String id = Json.text(dep, "modId", ""), type = Json.text(
                dep,
                "type",
                Json.text(dep, "mandatory", "true").equals("true") ? "required" : "optional"
            );
            if (id.equals("neoforge") || id.equals("forge")) continue;
            String range = fabricPredicate(Json.text(dep, "versionRange", "*"));
            if (id.equals("minecraft")) {
                depends.put("minecraft", range);
                continue;
            }
            switch (type) {
                case "required" -> depends.put(id, range);
                case "incompatible" -> breaks.put(id, range);
                case "discouraged" -> conflicts.put(id, range);
                default -> suggests.put(id, range);
            }
            notes.add("Dependency " + id + " (" + type + ") is kept by id; it needs a Fabric build of that mod.");
        }
        if (menu) suggests.put("modmenu", "*");
        out.put("depends", depends);
        if (!suggests.isEmpty()) out.put("suggests", suggests);
        if (!breaks.isEmpty()) out.put("breaks", breaks);
        if (!conflicts.isEmpty()) out.put("conflicts", conflicts);
        if (toml.get("[[accessTransformers]]") != null) leave(
            "src/main/resources/META-INF/neoforge.mods.toml",
            0,
            "accessTransformers",
            "access transformers are not converted to Fabric access wideners yet"
        );
        return pretty(out, "") + "\n";
    }

    /** JSON with one entry per line and tab indents, the way fabric.mod.json files are written. */
    static String pretty(Object value, String indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder out = new StringBuilder("{\n");
            int i = 0;
            for (var entry : map.entrySet()) {
                out
                    .append(indent)
                    .append('\t')
                    .append(Json.write(String.valueOf(entry.getKey())))
                    .append(": ")
                    .append(pretty(entry.getValue(), indent + "\t"));
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            return out.append(indent).append('}').toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder out = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) out
                .append(indent)
                .append('\t')
                .append(pretty(list.get(i), indent + "\t"))
                .append(i + 1 < list.size() ? ",\n" : "\n");
            return out.append(indent).append(']').toString();
        }
        return Json.write(value);
    }

    String fabricLoader = "0.16.0";

    String javaVersion() {
        Matcher m = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)|release\\s*=\\s*(\\d+)").matcher(
            readQuiet("build.gradle")
        );
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return "21";
    }

    String readQuiet(String relative) {
        try {
            return read(relative);
        } catch (IOException e) {
            return "";
        }
    }

    static final Pattern MDG_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"]net\\.neoforged\\.moddev['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    void buildToFabric(String minecraft, boolean menu) throws Exception {
        if (Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) < 0) throw new IOException(
            "NeoForge to Fabric is converted for Minecraft " +
            Route.FIRST_UNOBFUSCATED +
            " and later, where both loaders use Mojang's names; " +
            minecraft +
            " would need Fabric mappings."
        );
        String gradle = read("build.gradle");
        Matcher plugin = MDG_PLUGIN.matcher(gradle);
        if (!plugin.find()) throw new IOException(
            "build.gradle does not apply ModDevGradle (id 'net.neoforged.moddev') in its plugins block."
        );
        String loom = stablePlugin("https://maven.fabricmc.net", "net.fabricmc", "fabric-loom");
        fabricLoader = Json.text(
            Json.object(
                Json.array(Json.parse(Target.fetch(build, "https://meta.fabricmc.net/v2/versions/loader")))
                    .stream()
                    .filter(v -> Boolean.TRUE.equals(Json.object(v).get("stable")))
                    .findFirst()
                    .orElseThrow()
            ),
            "version",
            ""
        );
        List<String> apis = new ArrayList<>();
        for (String v : metadataVersions(
            Target.fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml")
        )) if (v.endsWith("+" + minecraft)) apis.add(v);
        if (apis.isEmpty()) throw new IOException("Fabric API has no release for Minecraft " + minecraft + ".");
        String api = newest(apis);
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            "id 'net.fabricmc.fabric-loom' version '" +
            loom +
            "'" +
            gradle.substring(plugin.end());
        int[] neo = block(gradle, "neoForge");
        StringBuilder loomBlock = new StringBuilder();
        if (neo != null) {
            Map<String, String> parts = children(gradle.substring(neo[2] + 1, neo[3] - 1));
            StringBuilder runs = new StringBuilder();
            for (var run : children(parts.getOrDefault("runs", "")).entrySet()) {
                if (
                    Set.of("client", "server").contains(run.getKey()) &&
                    run.getValue().strip().matches("client\\(\\)|server\\(\\)")
                ) continue;
                runs
                    .append(unit)
                    .append(unit)
                    .append(run.getKey())
                    .append(" {\n")
                    .append(runToLoom(run.getKey(), run.getValue()))
                    .append(unit)
                    .append(unit)
                    .append("}\n");
            }
            if (runs.length() > 0) loomBlock
                .append("loom {\n")
                .append(unit)
                .append("runs {\n")
                .append(runs)
                .append(unit)
                .append("}\n}\n");
            for (String key : parts.keySet()) if (!Set.of("runs", "mods").contains(key)) leave(
                "build.gradle",
                0,
                "neoForge " + key + " block",
                "no Loom counterpart in the rules"
            );
            gradle = gradle.substring(0, neo[0]) + loomBlock + gradle.substring(neo[1]);
        }
        gradle = gradle.replaceAll("(?m)^// NeoForge has no split client source set[^\\n]*\\n", "");
        int[] deps = block(gradle, "dependencies");
        String add =
            unit +
            "minecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n" +
            unit +
            "implementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n" +
            unit +
            "implementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n" +
            (menu
                    ? unit +
                    "compileOnly \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n" +
                    unit +
                    "localRuntime \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n"
                    : "");
        if (deps != null) {
            StringBuilder body = new StringBuilder();
            for (String raw : gradle.substring(deps[2] + 1, deps[3] - 1).lines().toList()) {
                String line = raw.strip();
                Matcher jar = Pattern.compile("^jarJar\\b").matcher(line);
                if (jar.find()) raw = raw.replaceFirst("jarJar", "include");
                if (!line.isEmpty()) leave(
                    "build.gradle",
                    0,
                    "dependency " + line,
                    "another mod or library on the NeoForge classpath: it needs its Fabric build, which convert cannot choose"
                );
                body.append(raw).append('\n');
            }
            gradle =
                gradle.substring(0, deps[2] + 1) +
                "\n" +
                add +
                body.toString().replaceAll("^\\n+", "") +
                gradle.substring(deps[3] - 1);
        } else gradle += "\ndependencies {\n" + add + "}\n";
        if (menu && !gradle.contains("maven.terraformersmc.com")) {
            int[] repos = block(gradle, "repositories");
            String repo = unit + "maven { url \"https://maven.terraformersmc.com/releases/\" }\n";
            if (repos != null) gradle =
                gradle.substring(0, repos[2] + 1) +
                "\n" +
                repo +
                gradle.substring(repos[2] + 1).replaceFirst("^\\n", "");
            else gradle = gradle.replaceFirst(
                "(?m)^dependencies\\s*\\{",
                "repositories {\n" + repo + "}\n\ndependencies {"
            );
        }
        gradle = gradle
            .replace("filesMatching(\"META-INF/neoforge.mods.toml\")", "filesMatching(\"fabric.mod.json\")")
            .replace("filesMatching('META-INF/neoforge.mods.toml')", "filesMatching('fabric.mod.json')");
        write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        props.put("neo_version", null);
        props.put("neoforge_version", null);
        props.put("loader_version", fabricLoader);
        props.put("fabric_api_version", api);
        if (menu) {
            // The newest stable Mod Menu that Modrinth, where Mod Menu publishes, lists for this Minecraft version on Fabric.
            String modmenu = null;
            for (Object version : Json.array(
                Json.parse(
                    Target.fetch(
                        build,
                        "https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%22" +
                        minecraft +
                        "%22%5D&loaders=%5B%22fabric%22%5D"
                    )
                )
            )) {
                String number = Json.text(Json.object(version), "version_number", "");
                if (!number.contains("-") && (modmenu == null || Versions.compare(number, modmenu) > 0)) modmenu =
                    number;
            }
            if (modmenu == null) throw new IOException(
                "Mod Menu has no release for Minecraft " +
                minecraft +
                " on Modrinth, so the config screen cannot move back to it."
            );
            props.put("modmenu_version", modmenu);
        }
        write("gradle.properties", setProperties(read("gradle.properties"), props));
        String settings = read("settings.gradle");
        if (!settings.contains("maven.fabricmc.net")) {
            String repo =
                unit +
                unit +
                "maven {\n" +
                unit +
                unit +
                unit +
                "name = 'Fabric'\n" +
                unit +
                unit +
                unit +
                "url = 'https://maven.fabricmc.net/'\n" +
                unit +
                unit +
                "}\n";
            if (settings.matches("(?s).*pluginManagement\\s*\\{\\s*repositories\\s*\\{.*")) settings =
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)\\n?",
                    "$1\n" + Matcher.quoteReplacement(repo)
                );
            else settings =
                "pluginManagement {\n" +
                unit +
                "repositories {\n" +
                repo +
                unit +
                unit +
                "mavenCentral()\n" +
                unit +
                unit +
                "gradlePluginPortal()\n" +
                unit +
                "}\n}\n\n" +
                settings;
            write("settings.gradle", settings);
        }
        notes.add("Build: Fabric Loom " + loom + ", Fabric Loader " + fabricLoader + ", Fabric API " + api + ".");
    }

    String runToLoom(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = unit + unit + unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher prop = Pattern.compile(
                "^systemProperty\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)?$"
            ).matcher(line);
            Matcher jvm = Pattern.compile("^jvmArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            Matcher dir = Pattern.compile(
                "^gameDirectory\\s*=\\s*project\\.file\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)$"
            ).matcher(line);
            Matcher program = Pattern.compile("^programArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.startsWith("ideName")) out
                .append(in)
                .append("name = ")
                .append(line.replaceFirst("^ideName\\s*=\\s*", ""))
                .append('\n');
            else if (prop.find()) out
                .append(in)
                .append("vmArg \"-D")
                .append(prop.group(1))
                .append('=')
                .append(prop.group(2))
                .append("\"\n");
            else if (jvm.find()) out.append(in).append("vmArg \"").append(jvm.group(1)).append("\"\n");
            else if (dir.find()) out.append(in).append("runDir = \"").append(dir.group(1)).append("\"\n");
            else if (program.find()) out.append(in).append("programArgs \"").append(program.group(1)).append("\"\n");
            else leave(
                "build.gradle",
                0,
                "neoForge run " + name + ": " + line,
                "this ModDevGradle run setting has no Loom counterpart in the rules"
            );
        }
        return out.toString();
    }

    // ---- check and report --------------------------------------------------------------------------------

    void compile(Map<String, Object> result) throws Exception {
        Build fresh = new Build(project, build.harness, "", build.report);
        Map<String, Object> model;
        try {
            model = fresh.model(true, false);
        } catch (IOException failure) {
            result.put("compiled", false);
            result.put("ok", false);
            result.put("compileError", failure.getMessage());
            return;
        }
        List<String> tasks = fresh.tasks(model, "classes");
        Run.Result run = fresh.gradle(tasks, "convert-compile");
        boolean ok = run.exit() == 0;
        result.put("compiled", ok);
        result.put("compileLog", run.log().toString());
        if (!ok) result.put("errors", Port.errors(run.log()));
        result.put("ok", ok && left.isEmpty());
    }

    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        out
            .append("Converted ")
            .append(result.get("from"))
            .append(" to ")
            .append(result.get("to"))
            .append(" (Minecraft ")
            .append(result.get("minecraft"))
            .append("): ");
        out
            .append(Json.array(result.get("changed")).size())
            .append(" file(s) changed, ")
            .append(Json.array(result.get("removed")).size())
            .append(" removed.\n");
        Map<String, Object> rulesApplied = Json.object(result.get("rulesApplied"));
        if (!rulesApplied.isEmpty()) out
            .append("Rules applied: ")
            .append(String.join(", ", rulesApplied.keySet()))
            .append('\n');
        for (Object note : Json.array(result.get("notes"))) out.append("Note: ").append(note).append('\n');
        List<Object> left = Json.array(result.get("left"));
        if (!left.isEmpty()) {
            out.append("Not converted (").append(left.size()).append("), named instead of dropped:\n");
            for (Object item : left) {
                var l = Json.object(item);
                Object line = l.get("line");
                out
                    .append("  ")
                    .append(l.get("file"))
                    .append(line instanceof Number n && n.intValue() > 0 ? ":" + n : "")
                    .append("  ")
                    .append(l.get("what"))
                    .append(": ")
                    .append(l.get("why"))
                    .append('\n');
            }
        }
        if (Boolean.TRUE.equals(result.get("compiled"))) out
            .append("The converted source compiles on ")
            .append(result.get("to"))
            .append(". Prove it works in the game next: modaccel ui-check or world-check with --assert.\n");
        else if (Boolean.FALSE.equals(result.get("compiled"))) {
            out.append("The converted source does not compile yet.");
            if (result.get("compileError") != null) out.append(' ').append(result.get("compileError"));
            List<Object> errors = result.get("errors") instanceof List<?> e ? Json.array(e) : List.of();
            for (Object e : errors.subList(0, Math.min(15, errors.size()))) {
                var error = Json.object(e);
                out
                    .append("\n  ")
                    .append(error.get("file"))
                    .append(':')
                    .append(error.get("line"))
                    .append(": ")
                    .append(error.get("message"));
            }
            out.append('\n');
        }
        out.append("Undo: modaccel convert --undo ").append(result.get("undoConvert"));
        return out.toString();
    }
}
