import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Two old calls on one value that the game replaced together (GameDiff's chains), rewritten in a mod's class into one
 * call of a stand-in static method on the first call's class; JarPort registers that method in the old release and
 * compiles the learned expression for it, as for any learned replacement.
 *
 * "last": the second call gives the value, and the first's value goes only to it (1.19.3's Vector3f.XP.rotationDegrees(f)).
 * "first": the second is a void call on the first's value, which is then kept in a local (1.21's
 * tesselator.getBuilder() then builder.begin(mode, format), now tesselator.begin(mode, format)).
 *
 * The first call's operands are set aside in fresh locals where it was, and null stands in for its value, so the stack
 * keeps its shape and the class's frames stay true; at the second call its arguments are set aside too, the stand-in
 * value dropped, and the stand-in method called with both calls' operands. Only where both calls are in one straight run
 * of code (nothing jumps in or out between them) and nothing else uses the first's value before the second.
 */
final class PortChains {

    /** One learned chain, in the old release's names. kind: 'm' a call, 'f' a field read. */
    record Spec(char kind, boolean xStatic, String xOwner, String xName, String xDesc, String yOwner, String yName, String yDesc, boolean yStatic, int at, boolean first,
        String fake, String fakeDesc, boolean ownerInterface) {}

    /** The old release's class that declares a member reached through owner, or null. */
    interface Declarer {
        String declaring(String owner, char kind, String name, String desc);
    }

    /** What to write at one instruction: the first call set aside, or the stand-in call at the second. */
    record Edit(Spec spec, boolean second, int slot) {}

    static byte[] apply(byte[] bytes, List<Spec> specs, Declarer declarer) {
        if (specs.isEmpty()) {
            return bytes;
        }
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(bytes);
        } catch (IllegalArgumentException unreadable) {
            return bytes;
        }
        String self = cm.thisClass().asInternalName();
        GameDiff.Origin origin = new GameDiff.Origin(bytes, Map.of(), null, new GameDiff.Names(null), new HashMap<>());
        Map<String, Map<Integer, Edit>> edits = new HashMap<>();
        Map<String, Integer> maxLocals = new HashMap<>();
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) {
                continue;
            }
            GameDiff.Decoder d = new GameDiff.Decoder(origin, self);
            GameDiff.Body body = d.run(mm);
            if (body == null || !body.whole) {
                continue;
            }
            Map<Integer, Edit> mine = find(body, d, specs, declarer);
            if (!mine.isEmpty()) {
                String key = mm.methodName().stringValue() + mm.methodType().stringValue();
                edits.put(key, mine);
                maxLocals.put(key, ((java.lang.classfile.attribute.CodeAttribute) mm.code().get()).maxLocals());
            }
        }
        if (edits.isEmpty()) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                Map<Integer, Edit> mine = edits.get(name + desc);
                return mine == null ? mv : new Rewriter(mv, mine, maxLocals.get(name + desc));
            }
        }, 0);
        return writer.toByteArray();
    }

    /** The chains one body makes, by the instruction each edit replaces. */
    static Map<Integer, Edit> find(GameDiff.Body body, GameDiff.Decoder d, List<Spec> specs, Declarer declarer) {
        Map<GameDiff.Node, List<GameDiff.Node>> parents = new IdentityHashMap<>();
        Set<GameDiff.Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<GameDiff.Node> todo = new ArrayDeque<>(body.statements);
        while (!todo.isEmpty()) {
            GameDiff.Node n = todo.pop();
            if (!seen.add(n)) {
                continue;
            }
            for (GameDiff.Node k : n.kids) {
                parents.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
                todo.push(k);
            }
        }
        Map<Integer, Integer> storesTo = new HashMap<>(); // local -> how many stores the method makes to it
        Map<Integer, GameDiff.Node> storeAt = new HashMap<>(); // instruction -> the store statement there
        for (GameDiff.Node st : body.statements) {
            if ("store".equals(st.value)) {
                storesTo.merge(st.slot, 1, Integer::sum);
                storeAt.put(st.at, st);
            }
        }
        Map<Integer, Edit> out = new HashMap<>();
        for (GameDiff.Node y : seen) {
            if (y.op != 'v' && y.op != 's' || y.kids.length == 0 || y.at < 0) {
                continue;
            }
            for (Spec s : specs) {
                if (s.at() < 0) {
                    // A sibling: Y on the receiver X was called on, earlier, with X's value kept in a local the method stores
                    // to only there, so that local holds it wherever Y runs.
                    if (y.op != 'v' || out.containsKey(y.at) || !y.ref.name().equals(s.yName()) || !y.ref.desc().equals(s.yDesc())
                        || !s.yOwner().equals(declarer.declaring(y.ref.owner(), 'm', y.ref.name(), y.ref.desc()))) {
                        continue;
                    }
                    for (GameDiff.Node x : parents.getOrDefault(y.kids[0], List.of())) {
                        GameDiff.Node st = x.at < 0 ? null : storeAt.get(x.at + 1);
                        if (x.op == 'v' && x != y && x.kids.length > 0 && x.kids[0] == y.kids[0] && x.at < y.at && x.ref.name().equals(s.xName())
                            && x.ref.desc().equals(s.xDesc()) && s.xOwner().equals(declarer.declaring(x.ref.owner(), 'm', x.ref.name(), x.ref.desc()))
                            && st != null && st.kids[0] == x && storesTo.get(st.slot) == 1) {
                            out.put(y.at, new Edit(s, true, st.slot));
                            break;
                        }
                    }
                    continue;
                }
                if (s.at() >= y.kids.length) {
                    continue;
                }
                GameDiff.Node x = y.kids[s.at()];
                if ("svgf".indexOf(x.op) < 0 || x.at < 0 || x.at >= y.at || x.ref == null) {
                    continue;
                }
                if (!matches(x, y, s, declarer) || !straight(d, x.at, y.at) || out.containsKey(x.at) || out.containsKey(y.at)) {
                    continue;
                }
                List<GameDiff.Node> users = parents.getOrDefault(x, List.of());
                int slot = -1;
                boolean ok;
                if (!s.first()) {
                    ok = users.size() == 1 && users.get(0) == y;
                } else {
                    ok = users.contains(y);
                    for (GameDiff.Node u : users) {
                        if (u == y) {
                            continue;
                        }
                        if ("store".equals(u.value) && u.at == x.at + 1 && slot < 0) {
                            slot = u.slot;
                        } else if (u.at <= y.at) {
                            ok = false;
                        }
                    }
                    ok &= slot >= 0;
                }
                if (ok) {
                    out.put(x.at, new Edit(s, false, -1));
                    out.put(y.at, new Edit(s, true, slot));
                }
            }
        }
        return out;
    }

    static boolean matches(GameDiff.Node x, GameDiff.Node y, Spec s, Declarer declarer) {
        char xk = x.op == 'g' || x.op == 'f' ? 'f' : 'm';
        if (xk != s.kind() || !x.ref.name().equals(s.xName()) || !y.ref.name().equals(s.yName()) || !y.ref.desc().equals(s.yDesc())) {
            return false;
        }
        if (xk == 'm' && !x.ref.desc().equals(s.xDesc())) {
            return false;
        }
        String xo = declarer.declaring(x.ref.owner(), xk, x.ref.name(), xk == 'f' ? null : x.ref.desc());
        String yo = declarer.declaring(y.ref.owner(), 'm', y.ref.name(), y.ref.desc());
        boolean xStatic = x.op == 's' || x.op == 'g';
        return s.xOwner().equals(xo) && s.yOwner().equals(yo) && xStatic == s.xStatic() && (y.op == 's') == s.yStatic() && (s.first() == y.type.equals("V"));
    }

    /** Nothing jumps into the run after `from`, up to and with `to`, and nothing in it jumps out. */
    static boolean straight(GameDiff.Decoder d, int from, int to) {
        for (int i = from + 1; i <= to; i++) {
            if (d.starts.contains(i)) {
                return false;
            }
        }
        for (int i = from; i < to; i++) {
            if (d.jumps.contains(i)) {
                return false;
            }
        }
        return true;
    }

    /** Writes the method again with each chain's two calls replaced. */
    static final class Rewriter extends MethodVisitor {

        final Map<Integer, Edit> edits;
        final Map<Spec, int[]> held = new HashMap<>(); // the first call's operands, by local, until the second
        int index = -1;
        int next;

        Rewriter(MethodVisitor mv, Map<Integer, Edit> edits, int maxLocals) {
            super(Opcodes.ASM9, mv);
            this.edits = edits;
            this.next = maxLocals;
        }

        /** Stores the values of these types, top of stack last, in fresh locals; returns their slots. */
        int[] spill(Type[] types) {
            int[] slots = new int[types.length];
            for (int i = 0; i < types.length; i++) {
                slots[i] = next;
                next += types[i].getSize();
            }
            for (int i = types.length - 1; i >= 0; i--) {
                super.visitVarInsn(types[i].getOpcode(Opcodes.ISTORE), slots[i]);
            }
            return slots;
        }

        void load(Type[] types, int[] slots) {
            for (int i = 0; i < types.length; i++) {
                super.visitVarInsn(types[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
        }

        static Type[] firstOperands(Spec s) {
            List<Type> t = new ArrayList<>();
            if (!s.xStatic()) {
                t.add(Type.getObjectType(s.xOwner()));
            }
            if (s.kind() == 'm') {
                t.addAll(List.of(Type.getArgumentTypes(s.xDesc())));
            }
            return t.toArray(new Type[0]);
        }

        /** True when the instruction was replaced. */
        boolean edit() {
            index++;
            Edit e = edits.get(index);
            if (e == null) {
                return false;
            }
            Spec s = e.spec();
            if (s.at() < 0) {
                // Y's receiver stays where it is; X's value is loaded from its local after it, then Y's arguments.
                Type[] args = Type.getArgumentTypes(s.yDesc());
                int[] slots = spill(args);
                super.visitVarInsn(Opcodes.ALOAD, e.slot());
                load(args, slots);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, s.xOwner(), s.fake(), s.fakeDesc(), s.ownerInterface());
                return true;
            }
            Type[] xs = firstOperands(s);
            if (!e.second()) {
                int[] slots = spill(xs);
                held.put(s, slots);
                super.visitInsn(Opcodes.ACONST_NULL);
                return true;
            }
            List<Type> all = new ArrayList<>();
            if (!s.yStatic()) {
                all.add(Type.getObjectType(s.yOwner()));
            }
            all.addAll(List.of(Type.getArgumentTypes(s.yDesc())));
            Type[] ys = all.toArray(new Type[0]);
            int[] ySlots = spill(ys); // the stand-in null among them, where the first call's value was
            load(xs, held.remove(s));
            for (int i = 0; i < ys.length; i++) {
                if (i != s.at()) {
                    super.visitVarInsn(ys[i].getOpcode(Opcodes.ILOAD), ySlots[i]);
                }
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, s.xOwner(), s.fake(), s.fakeDesc(), s.ownerInterface());
            if (s.first()) {
                super.visitVarInsn(Opcodes.ASTORE, e.slot());
            }
            return true;
        }

        @Override
        public void visitInsn(int opcode) {
            if (!edit()) {
                super.visitInsn(opcode);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!edit()) {
                super.visitIntInsn(opcode, operand);
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            if (!edit()) {
                super.visitVarInsn(opcode, var);
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!edit()) {
                super.visitTypeInsn(opcode, type);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!edit()) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (!edit()) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
            if (!edit()) {
                super.visitInvokeDynamicInsn(name, descriptor, bsm, args);
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (!edit()) {
                super.visitJumpInsn(opcode, label);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!edit()) {
                super.visitLdcInsn(value);
            }
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            if (!edit()) {
                super.visitIincInsn(var, increment);
            }
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            if (!edit()) {
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            if (!edit()) {
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dims) {
            if (!edit()) {
                super.visitMultiANewArrayInsn(descriptor, dims);
            }
        }
    }
}
