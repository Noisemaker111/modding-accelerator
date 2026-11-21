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
 * "late": a value the new release takes where the object is made, which the mod sets later on the same object (1.21.11's
 * CycleButton.builder(f) ... withInitialValue(v), now builder(f, v)). Y's receiver is X's value through calls that give
 * their receiver back, each used only by the next; Y's argument is a plain value (constants, static and instance fields,
 * parameters the method never stores to, casts, boxing) written again where X was, so X's stand-in takes it; at Y only
 * the argument is dropped and the object goes on.
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

    /** What to write at one instruction: the first call set aside, or the stand-in call at the second; value: a late chain's moved argument. */
    record Edit(Spec spec, boolean second, int slot, GameDiff.Node value, Map<GameDiff.Node, Integer> held, java.util.function.Function<String, Boolean> interfaces) {
        Edit(Spec spec, boolean second, int slot) {
            this(spec, second, slot, null, Map.of(), owner -> null);
        }
    }

    static byte[] apply(byte[] bytes, List<Spec> specs, Declarer declarer) {
        return apply(bytes, specs, declarer, owner -> null);
    }

    /** interfaces: whether a class is an interface (null when unknown), for a late chain's argument written again. */
    static byte[] apply(byte[] bytes, List<Spec> specs, Declarer declarer, java.util.function.Function<String, Boolean> interfaces) {
        if (specs.isEmpty()) {
            return bytes;
        }
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(bytes);
        } catch (IllegalArgumentException unreadable) {
            return bytes;
        }
        // A pair is two members named in this class; most classes name neither, and decoding every method to find that out was a tenth of a port.
        Set<String> named = new HashSet<>();
        for (var entry : cm.constantPool()) {
            if (entry instanceof java.lang.classfile.constantpool.Utf8Entry text) {
                named.add(text.stringValue());
            }
        }
        if (specs.stream().noneMatch(s -> named.contains(s.xName()) && named.contains(s.yName()))) {
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
            Map<Integer, Edit> mine = find(body, d, specs, declarer, interfaces);
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
        return find(body, d, specs, declarer, owner -> null);
    }

    static Map<Integer, Edit> find(GameDiff.Body body, GameDiff.Decoder d, List<Spec> specs, Declarer declarer, java.util.function.Function<String, Boolean> interfaces) {
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
                if (s.at() == -2) {
                    late(y, s, d, parents, storesTo, storeAt, declarer, interfaces, out);
                    continue;
                }
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

    /** A late chain ending at y: X found through Y's receiver, Y's argument movable to where X is. */
    static void late(GameDiff.Node y, Spec s, GameDiff.Decoder d, Map<GameDiff.Node, List<GameDiff.Node>> parents, Map<Integer, Integer> storesTo,
        Map<Integer, GameDiff.Node> storeAt, Declarer declarer, java.util.function.Function<String, Boolean> interfaces, Map<Integer, Edit> out) {
        if (y.op != 'v' || y.kids.length != 2 || out.containsKey(y.at) || !y.ref.name().equals(s.yName()) || !y.ref.desc().equals(s.yDesc())
            || !s.yOwner().equals(declarer.declaring(y.ref.owner(), 'm', y.ref.name(), y.ref.desc()))) {
            return;
        }
        GameDiff.Node next = y, x = y.kids[0];
        while (true) {
            List<GameDiff.Node> users = parents.getOrDefault(x, List.of());
            if (users.size() != 1 || users.get(0) != next) {
                return; // the object is used elsewhere before Y: moving Y's value to X could change what that use sees
            }
            if (x.op == 's' || x.op == 'v') {
                String xo = x.ref == null ? null : declarer.declaring(x.ref.owner(), 'm', x.ref.name(), x.ref.desc());
                if (x.ref != null && x.ref.name().equals(s.xName()) && x.ref.desc().equals(s.xDesc()) && s.xOwner().equals(xo) && (x.op == 's') == s.xStatic()) {
                    break;
                }
            }
            if (x.op != 'v' || x.kids.length == 0 || !x.type.equals(x.kids[0].type)) {
                return;
            }
            next = x;
            x = x.kids[0];
        }
        // A local stored once, before X, is loaded again rather than computed again (the decoder writes its value in).
        Map<GameDiff.Node, Integer> held = new IdentityHashMap<>();
        for (GameDiff.Node st : storeAt.values()) {
            if (st.at < x.at && st.kids.length == 1 && storesTo.getOrDefault(st.slot, 0) == 1) {
                held.put(st.kids[0], st.slot);
            }
        }
        GameDiff.Node value = y.kids[1];
        if (x.at < 0 || x.at >= y.at || out.containsKey(x.at) || !straight(d, x.at, y.at) || !movable(value, storesTo, held, interfaces, 0)) {
            return;
        }
        out.put(x.at, new Edit(s, false, -1, value, held, interfaces));
        out.put(y.at, new Edit(s, true, -1, value, held, interfaces));
    }

    /**
     * A value that can be read where X is instead of where Y is: constants, fields, parameters never stored to, locals stored
     * once before X, and casts and calls over those (getters: the game reads the value where the object is made, so do
     * we). A call's class must be known as an interface or not, to write the call again.
     */
    static boolean movable(GameDiff.Node n, Map<Integer, Integer> storesTo, Map<GameDiff.Node, Integer> held,
        java.util.function.Function<String, Boolean> interfaces, int depth) {
        if (held.containsKey(n)) {
            return true;
        }
        if (depth > 6) {
            return false;
        }
        for (GameDiff.Node k : n.kids) {
            if (!movable(k, storesTo, held, interfaces, depth + 1)) {
                return false;
            }
        }
        return switch (n.op) {
            case 'k', 'n' -> true;
            case 'p' -> n.value instanceof Integer slot && !storesTo.containsKey(slot);
            case 'g' -> n.kids.length == 0;
            case 'f', 't' -> n.kids.length == 1;
            case 's', 'v' -> n.ref != null && n.at >= 0 && !n.ref.name().startsWith("<") && (n.op == 's' || n.kids.length >= 1) && interfaces.apply(n.ref.owner()) != null;
            default -> false;
        };
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
            if (s.at() == -2) {
                if (!e.second()) {
                    // X's operands are on the stack: Y's value is written again after them, and the stand-in takes both.
                    emit(e.value(), e);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, s.xOwner(), s.fake(), s.fakeDesc(), s.ownerInterface());
                } else {
                    super.visitInsn(Type.getArgumentTypes(s.yDesc())[0].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                }
                return true;
            }
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

        /** Writes a movable value (see movable) again. */
        void emit(GameDiff.Node n, Edit e) {
            Integer local = e.held().get(n);
            if (local != null) {
                super.visitVarInsn(Type.getType(n.type).getOpcode(Opcodes.ILOAD), local);
                return;
            }
            switch (n.op) {
                case 'k' -> super.visitLdcInsn(n.value);
                case 'n' -> super.visitInsn(Opcodes.ACONST_NULL);
                case 'p' -> super.visitVarInsn(Type.getType(n.type).getOpcode(Opcodes.ILOAD), (Integer) n.value);
                case 'g' -> super.visitFieldInsn(Opcodes.GETSTATIC, n.ref.owner(), n.ref.name(), n.ref.desc());
                case 'f' -> {
                    emit(n.kids[0], e);
                    super.visitFieldInsn(Opcodes.GETFIELD, n.ref.owner(), n.ref.name(), n.ref.desc());
                }
                case 't' -> {
                    emit(n.kids[0], e);
                    super.visitTypeInsn(Opcodes.CHECKCAST, n.ref.owner());
                }
                case 's', 'v' -> {
                    for (GameDiff.Node k : n.kids) {
                        emit(k, e);
                    }
                    boolean itf = Boolean.TRUE.equals(e.interfaces().apply(n.ref.owner()));
                    int op = n.op == 's' ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                    super.visitMethodInsn(op, n.ref.owner(), n.ref.name(), n.ref.desc(), itf);
                }
                default -> throw new IllegalStateException("not movable: " + n.op);
            }
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
