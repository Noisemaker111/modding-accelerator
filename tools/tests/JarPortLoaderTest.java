import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * The loader a mod runs on, read and linked like the game, each part on this JVM with real class files:
 * - a mod's call to a loader method the new release dropped (NeoForge's FML, Fabric API) is reported, and one it kept links;
 * - an interface Fabric API adds to a game class ("loom:injected_interfaces") makes the mod's call through the game class link;
 * - NeoForge's own pom names the libraries its loader is made of, and NeoForm's mappings the SRG names its mods use;
 * - a NeoForge mod in SRG names (classes, access transformer, nested jars) is read in Mojang's names.
 */
final class JarPortLoaderTest {

    static int failures;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        loaderLinks(scratch);
        pom();
        srg();
        if (failures > 0) {
            System.out.println(failures + " loader checks failed");
            System.exit(1);
        }
        System.out.println("loader checks passed");
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    static void loaderLinks(Path scratch) throws Exception {
        byte[] thing = JarPortTest.real("net/minecraft/Stack", "java/lang/Object", 0, new String[0], w -> { });
        byte[] fabricStack = JarPortTest.real("net/fabricmc/fabric/api/item/FabricStack", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0],
            w -> body(w, Opcodes.ACC_PUBLIC, "getRemainder", "()I", m -> { m.visitInsn(Opcodes.ICONST_0); m.visitInsn(Opcodes.IRETURN); }));
        GameDiff.Snap[] snaps = new GameDiff.Snap[2];
        for (int i = 0; i < 2; i++) {
            boolean old = i == 0;
            List<byte[]> loader = new ArrayList<>(List.of(fabricStack,
                JarPortTest.real("net/neoforged/fml/ModLoadingContext", "java/lang/Object", 0, new String[0], w -> {
                    staticMethod(w, "get", "()Lnet/neoforged/fml/ModLoadingContext;");
                    if (old) {
                        body(w, Opcodes.ACC_PUBLIC, "registerConfig", "(I)V", m -> m.visitInsn(Opcodes.RETURN));
                    }
                    body(w, Opcodes.ACC_PUBLIC, "kept", "()V", m -> m.visitInsn(Opcodes.RETURN));
                })));
            if (old) {
                loader.add(JarPortTest.real("net/fabricmc/fabric/api/registry/FuelRegistry", "java/lang/Object", 0, new String[0], w -> { }));
            }
            List<byte[]> game = Exposure.injected(List.of(thing), null, Map.of("net/minecraft/Stack", List.of("net/fabricmc/fabric/api/item/FabricStack")));
            snaps[i] = GameDiff.read(old ? "1.0" : "2.0", game, null, loader);
        }
        Path map = scratch.resolve("route-loader.json");
        Files.writeString(map, "{}");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"m6\", \"version\": \"1\", \"depends\": {\"minecraft\": \"1.0\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("m6/Init.class", JarPortTest.real("m6/Init", "java/lang/Object", 0, new String[0], w -> body(w, Opcodes.ACC_PUBLIC, "run", "()V", m -> {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/fml/ModLoadingContext", "get", "()Lnet/neoforged/fml/ModLoadingContext;", false);
            m.visitInsn(Opcodes.DUP);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/fml/ModLoadingContext", "registerConfig", "(I)V", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/fml/ModLoadingContext", "kept", "()V", false);
            m.visitTypeInsn(Opcodes.NEW, "net/minecraft/Stack");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/Stack", "<init>", "()V", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Stack", "getRemainder", "()I", false);
            m.visitInsn(Opcodes.POP);
            m.visitLdcInsn(Type.getObjectType("net/fabricmc/fabric/api/registry/FuelRegistry"));
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
        })));
        List<Map<String, Object>> results = new ArrayList<>();
        JarPort port = new JarPort(snaps[0], snaps[1], map);
        port.loader = "fabric";
        port.port(JarPortTest.jar(files), "m6.jar", results);
        List<String> unlinked = results.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
        expect(unlinked.stream().anyMatch(u -> u.contains("net.neoforged.fml.ModLoadingContext.registerConfig(I)V")),
            "a call to a loader method the new release dropped is reported, like a game method: " + unlinked);
        expect(unlinked.stream().anyMatch(u -> u.contains("net.fabricmc.fabric.api.registry.FuelRegistry")),
            "a Fabric API class the new release dropped is reported: " + unlinked);
        expect(unlinked.stream().noneMatch(u -> u.contains("kept") || u.contains("ModLoadingContext.get")),
            "a loader method both releases have links: " + unlinked);
        expect(unlinked.stream().noneMatch(u -> u.contains("getRemainder")),
            "a method Fabric API adds to a game class through an injected interface links from the game class: " + unlinked);
        GameDiff.Cls stack = snaps[1].classes.get("net/minecraft/Stack");
        expect(stack != null && stack.ifaces.contains("net/fabricmc/fabric/api/item/FabricStack") && !stack.loader,
            "the game class carries the injected interface and stays the game's: " + (stack == null ? null : stack.ifaces));
        expect(Exposure.loaderPart(snaps[0], "net.neoforged.fml.ModLoadingContext.registerConfig(I)V") && !Exposure.loaderPart(snaps[0], "net.minecraft.Stack"),
            "the scoreboard labels a loader member as the loader's and a game class as the game's");
    }

    static void pom() {
        String pom = """
            <project><dependencies>
            <dependency><groupId>net.neoforged</groupId><artifactId>neoform</artifactId><version>1.21-20240613.152323</version><scope>compile</scope></dependency>
            <dependency><groupId>net.neoforged</groupId><artifactId>neoform</artifactId><version>1.21-20240613.152323</version><type>zip</type>
              <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
            <dependency><groupId>net.neoforged.fancymodloader</groupId><artifactId>loader</artifactId><version>4.0.23</version>
              <exclusions><exclusion><groupId>org.slf4j</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
            <dependency><groupId>net.neoforged</groupId><artifactId>mergetool</artifactId><version>2.0.0</version><classifier>api</classifier></dependency>
            <dependency><groupId>net.neoforged</groupId><artifactId>bus</artifactId><version>8.0.1</version></dependency>
            </dependencies></project>
            """;
        List<String> found = Exposure.dependencies(pom).stream().map(d -> String.join(":", d)).toList();
        expect(found.contains("net.neoforged.fancymodloader:loader:4.0.23") && found.contains("net.neoforged:bus:8.0.1"),
            "NeoForge's pom gives its loader and event bus with their versions: " + found);
        expect(found.stream().noneMatch(d -> d.contains("mergetool") || d.startsWith("*") || d.startsWith("org.slf4j")) && found.stream().filter(d -> d.contains("neoform")).count() == 1,
            "a dependency with a classifier or a zip type, and the groups an exclusion names, are not libraries a mod links against: " + found);
    }

    static void srg() throws Exception {
        VersionMap.Proguard pg = VersionMap.Proguard.parse("""
            net.minecraft.Thing -> a:
                int count -> b
                void tick() -> c
                void tick(int) -> d
            """);
        String tsrg = "tsrg2 obf srg id\na net/minecraft/Thing\n\tb f_100_ 100\n\tc ()V m_200_ 200\n\t\tstatic\n\td (I)V m_300_ 300\n\t\t0 o p_300_0_\n";
        Map<String, String> members = Exposure.Intermediary.srg(tsrg, pg);
        expect("count".equals(members.get("f_100_")) && "tick".equals(members.get("m_200_")) && "tick".equals(members.get("m_300_")) && !members.containsKey("p_300_0_"),
            "NeoForm's SRG ids join Mojang's names through the obfuscated game: " + members);
        ModNames names = new ModNames(new Exposure.Intermediary(Map.of(), members));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"m7\"\n".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/accesstransformer.cfg", "public net.minecraft.Thing f_100_ # count\npublic net.minecraft.Thing m_300_(I)V\n".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/jarjar/metadata.json", "{\"jars\": [{\"path\": \"META-INF/jarjar/inner.jar\"}]}".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/jarjar/inner.jar", JarPortTest.jar(Map.of("m7/Inner.class", user("m7/Inner"))));
        files.put("m7/User.class", user("m7/User"));
        Map<String, byte[]> read = JarPortTest.entries(names.jar(JarPortTest.jar(files), new ModNames.Counts()));
        String calls = String.join(" ", refs(read.get("m7/User.class")));
        expect(calls.contains("net/minecraft/Thing.tick()V") && calls.contains("net/minecraft/Thing.count") && !calls.contains("m_200_"),
            "a NeoForge mod's SRG references are read in Mojang's names: " + calls);
        String at = new String(read.get("META-INF/accesstransformer.cfg"), StandardCharsets.UTF_8);
        expect(at.contains("public net.minecraft.Thing count") && at.contains("public net.minecraft.Thing tick(I)V"),
            "its access transformer, which the loader reads in the same names, is read in Mojang's names: " + at);
        String inner = String.join(" ", refs(JarPortTest.entries(read.get("META-INF/jarjar/inner.jar")).get("m7/Inner.class")));
        expect(inner.contains("net/minecraft/Thing.tick()V"), "a jar NeoForge's jarjar list nests is read the same way: " + inner);
    }

    /** A mod class that calls Thing.m_200_() and reads Thing.f_100_, as a NeoForge mod before 1.20.5 ships. */
    static byte[] user(String name) {
        return JarPortTest.real(name, "java/lang/Object", 0, new String[0], w -> body(w, Opcodes.ACC_PUBLIC, "run", "(Lnet/minecraft/Thing;)V", m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Thing", "m_200_", "()V", false);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/Thing", "f_100_", "I");
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
        }));
    }

    static void body(ClassWriter w, int access, String name, String desc, java.util.function.Consumer<MethodVisitor> code) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        m.visitCode();
        code.accept(m);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void staticMethod(ClassWriter w, String name, String desc) {
        body(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, m -> {
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ARETURN);
        });
    }

    /** owner.name+desc of every call and owner.name of every field use in a class. */
    static List<String> refs(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        out.add(owner + "." + n + d);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        out.add(owner + "." + n);
                    }
                };
            }
        }, 0);
        return out;
    }
}
