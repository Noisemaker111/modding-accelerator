import java.lang.classfile.*;
import java.lang.constant.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Offline checks for GameDiff's matcher: two small synthetic "releases" built with the ClassFile API.
 *
 *   v1                                   v2
 *   Widget.computeTotal(I)I              Widget.sumUp(I)I            same body: a rename
 *   Widget.tick()V                       Widget.tick(F)V             same name: a signature change
 *   Widget.label, Widget.getLabel()      (gone)                      removed
 *   Widget.oldThing()V                   Widget.newThing()V          different strings: NOT linked
 *   OldName { alpha, beta, gamma, size } NewName { same members }    a renamed class
 *   Helper.checksum([I)I                 Util.checksum([I)I          same non-trivial body: a move
 *   Rules.RULE_ALPHA = reg("alpha_rule") Rules.ALPHA = reg("alpha_rule")   same registration id: a field rename
 *   Rules.RULE_DOX = reg("doX")          Rules.X_ENABLED = reg("minecraft:x_enabled"), and a v2 datafix
 *                                        renames "doX" -> "minecraft:x_enabled": a field rename
 *   Rules.RULE_BETA = reg("beta_rule")   Rules.GAMMA = reg("gamma_rule")   different ids: NOT linked
 *
 *   javac --release 25 -d <dir> tools/VersionMap.java tools/GameDiff.java tools/GameDiffTest.java
 *   java -cp <dir> GameDiffTest
 * Exit 0 when every check passes, 1 otherwise.
 */
public final class GameDiffTest {

    static int failures = 0;
    static final String P = "net/minecraft/test/";

    public static void main(String[] args) throws Exception {
        GameDiff.Snap v1 = GameDiff.read("v1", release(1), null, List.of());
        GameDiff.Snap v2 = GameDiff.read("v2", release(2), null, List.of());
        GameDiff.Hop h = new GameDiff.Hop(v1, v2);
        h.run();

        GameDiff.Cls oldName = v1.classes.get(P + "OldName");
        check(h.cAB.get(oldName) == v2.classes.get(P + "NewName"), "renamed class OldName -> NewName");
        check(h.cAB.get(v1.classes.get(P + "Widget")) == v2.classes.get(P + "Widget"), "Widget kept by name");
        check(
            h.mAB.get(v1.method(P + "OldName", "beta", "(I)I")) == v2.method(P + "NewName", "beta", "(I)I"),
            "members follow the renamed class"
        );
        check(
            h.mAB.get(v1.method(P + "Widget", "computeTotal", "(I)I")) == v2.method(P + "Widget", "sumUp", "(I)I"),
            "renamed method computeTotal -> sumUp"
        );
        check(
            h.mAB.get(v1.method(P + "Widget", "tick", "()V")) == v2.method(P + "Widget", "tick", "(F)V"),
            "signature change tick()V -> tick(F)V"
        );
        check(
            h.mAB.get(v1.method(P + "Helper", "checksum", "([I)I")) == v2.method(P + "Util", "checksum", "([I)I"),
            "moved static method Helper.checksum -> Util.checksum"
        );
        check(v1.field(P + "Widget", "label") != null && h.fAB.get(v1.field(P + "Widget", "label")) == null, "removed field label");
        check(h.mAB.get(v1.method(P + "Widget", "getLabel", "()Ljava/lang/String;")) == null, "removed method getLabel");
        check(h.mAB.get(v1.method(P + "Widget", "oldThing", "()V")) == null, "unrelated oldThing is not linked to newThing");
        check(h.fAB.get(v1.field(P + "OldName", "size")) == v2.field(P + "NewName", "size"), "field follows the renamed class");
        check(h.fAB.get(v1.field(P + "Rules", "RULE_ALPHA")) == v2.field(P + "Rules", "ALPHA"), "same registration id: RULE_ALPHA -> ALPHA");
        check(h.fAB.get(v1.field(P + "Rules", "RULE_DOX")) == v2.field(P + "Rules", "X_ENABLED"), "datafix id rename: RULE_DOX -> X_ENABLED");
        check(h.fAB.get(v1.field(P + "Rules", "RULE_BETA")) == null, "different registration ids are not linked");

        // The same two releases through VersionMap.Chain: the sections the porter reads.
        GameDiff.Driver d = new GameDiff.Driver(List.of("v1", "v2"), null, null);
        Map<String, GameDiff.Snap> snaps = Map.of(
            "v1",
            GameDiff.read("v1", release(1), null, List.of()),
            "v2",
            GameDiff.read("v2", release(2), null, List.of())
        );
        VersionMap.Chain c = VersionMap.Chain.of(List.of("v1", "v2"), v -> d.next(snaps.get(v), null, null));
        String json = d.json(c);
        check(json.contains("\"linked\": \"game-bytecode\""), "map says how it was linked");
        check(json.contains("{ \"from\": \"net.minecraft.test.OldName\", \"to\": \"net.minecraft.test.NewName\" }"), "classes section");
        check(json.contains("\"from\": \"computeTotal\", \"desc\": \"(I)I\", \"to\": \"sumUp\""), "methods section");
        check(json.contains("\"from\": \"checksum\", \"desc\": \"([I)I\", \"to\": \"checksum\", \"newDesc\": \"([I)I\", \"newOwner\": \"net.minecraft.test.Util\""), "methodsMoved section");
        check(json.contains("\"from\": \"tick\", \"desc\": \"()V\", \"to\": \"tick\", \"newDesc\": \"(F)V\""), "signatures section");
        check(json.contains("{ \"owner\": \"net.minecraft.test.Widget\", \"from\": \"label\", \"desc\": \"Ljava/lang/String;\" }"), "fieldsRemoved section");
        check(!json.contains("\"to\": \"newThing\""), "no guessed rename in the map");

        System.out.println(failures == 0 ? "GameDiffTest: all checks passed" : "GameDiffTest: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }

    static ClassDesc cd(String internal) {
        return ClassDesc.ofInternalName(internal);
    }

    static final ClassDesc STRING = ClassDesc.of("java.lang.String");
    static MethodTypeDesc logType() {
        return MethodTypeDesc.of(ConstantDescs.CD_void, STRING);
    }

    static byte[] cls(String name, Consumer<ClassBuilder> body) {
        return ClassFile.of().build(cd(name), cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withSuperclass(ConstantDescs.CD_Object);
            body.accept(cb);
        });
    }

    static void log(CodeBuilder c, String s) {
        c.ldc(s);
        c.invokestatic(cd(P + "Helper"), "log", logType());
    }

    static void checksum(ClassBuilder cb) {
        cb.withMethodBody("checksum", MethodTypeDesc.ofDescriptor("([I)I"), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, c -> {
            log(c, "checksum");
            c.aload(0);
            c.arraylength();
            c.bipush(31);
            c.imul();
            c.aload(0);
            c.iconst_0();
            c.iaload();
            c.iadd();
            c.ireturn();
        });
    }

    static List<byte[]> release(int v) {
        List<byte[]> out = new ArrayList<>();
        out.add(cls(P + "Helper", cb -> {
            cb.withMethodBody("log", logType(), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, c -> c.return_());
            if (v == 1) {
                checksum(cb);
            }
        }));
        out.add(cls(P + "Util", cb -> {
            cb.withMethodBody("unrelated", MethodTypeDesc.ofDescriptor("()I"), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, c -> {
                c.iconst_3();
                c.ireturn();
            });
            if (v == 2) {
                checksum(cb);
            }
        }));
        String renamed = v == 1 ? "OldName" : "NewName";
        out.add(cls(P + renamed, cb -> {
            cb.withField("size", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC);
            cb.withField("weight", ConstantDescs.CD_float, ClassFile.ACC_PUBLIC);
            cb.withMethodBody("alpha", MethodTypeDesc.ofDescriptor("()V"), ClassFile.ACC_PUBLIC, c -> c.return_());
            cb.withMethodBody("beta", MethodTypeDesc.ofDescriptor("(I)I"), ClassFile.ACC_PUBLIC, c -> {
                c.iload(1);
                c.iconst_2();
                c.imul();
                c.ireturn();
            });
            cb.withMethodBody("gamma", MethodTypeDesc.ofDescriptor("()Ljava/lang/String;"), ClassFile.ACC_PUBLIC, c -> {
                c.ldc("gamma");
                c.areturn();
            });
            cb.withMethodBody("delta", MethodTypeDesc.ofDescriptor("()I"), ClassFile.ACC_PUBLIC, c -> {
                c.aload(0);
                c.getfield(cd(P + renamed), "size", ConstantDescs.CD_int);
                c.ireturn();
            });
        }));
        out.add(cls(P + "Widget", cb -> {
            ClassDesc self = cd(P + "Widget");
            cb.withField("count", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC);
            if (v == 1) {
                cb.withField("label", STRING, ClassFile.ACC_PUBLIC);
                cb.withMethodBody("getLabel", MethodTypeDesc.ofDescriptor("()Ljava/lang/String;"), ClassFile.ACC_PUBLIC, c -> {
                    c.aload(0);
                    c.getfield(self, "label", STRING);
                    c.areturn();
                });
            }
            cb.withMethodBody(v == 1 ? "computeTotal" : "sumUp", MethodTypeDesc.ofDescriptor("(I)I"), ClassFile.ACC_PUBLIC, c -> {
                c.aload(0);
                c.getfield(self, "count", ConstantDescs.CD_int);
                c.iload(1);
                c.imul();
                log(c, "total");
                c.ireturn();
            });
            cb.withMethodBody("tick", MethodTypeDesc.ofDescriptor(v == 1 ? "()V" : "(F)V"), ClassFile.ACC_PUBLIC, c -> {
                c.aload(0);
                c.dup();
                c.getfield(self, "count", ConstantDescs.CD_int);
                c.iconst_1();
                c.iadd();
                c.putfield(self, "count", ConstantDescs.CD_int);
                c.return_();
            });
            cb.withMethodBody(v == 1 ? "oldThing" : "newThing", MethodTypeDesc.ofDescriptor("()V"), ClassFile.ACC_PUBLIC, c -> {
                log(c, v == 1 ? "old thing" : "new thing");
                c.return_();
            });
        }));
        out.add(cls(P + "Rules", cb -> {
            ClassDesc self = cd(P + "Rules");
            String[][] rules = v == 1
                ? new String[][] { { "RULE_ALPHA", "alpha_rule" }, { "RULE_DOX", "doX" }, { "RULE_BETA", "beta_rule" } }
                : new String[][] { { "ALPHA", "alpha_rule" }, { "X_ENABLED", "minecraft:x_enabled" }, { "GAMMA", "gamma_rule" } };
            for (String[] r : rules) {
                cb.withField(r[0], STRING, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            }
            cb.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, c -> {
                for (String[] r : rules) {
                    c.ldc(r[1]);
                    c.putstatic(self, r[0], STRING);
                }
                c.return_();
            });
        }));
        if (v == 2) {
            // The game's own rename table: an (old id, new id) pair of consecutive string constants in a datafix.
            out.add(cls("net/minecraft/util/datafix/fixes/TestRuleFix", cb -> {
                cb.withMethodBody("fix", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, c -> {
                    c.ldc("doX");
                    c.ldc("minecraft:x_enabled");
                    c.invokestatic(cd(P + "Helper"), "rename", MethodTypeDesc.of(ConstantDescs.CD_void, STRING, STRING));
                    c.return_();
                });
            }));
        }
        return out;
    }
}
