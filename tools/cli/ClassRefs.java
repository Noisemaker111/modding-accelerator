import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * What one class file refers to, read from its constant pool and its methods' bytecode; no class is loaded.
 * pack-check uses it to tell which game classes a mod's server entrypoint reaches, and which JDK members a loader calls.
 */
final class ClassRefs {

    /** "owner.name:descriptor" of every method this class calls (Methodref and InterfaceMethodref). */
    final Set<String> methodRefs = new LinkedHashSet<>();
    /** "name:descriptor" of every method this class declares. */
    final Set<String> declared = new LinkedHashSet<>();
    final String name;
    /** The superclass and interfaces: loading this class loads them. */
    final List<String> supertypes = new ArrayList<>();
    /** Each declared method ("name:descriptor") to the types its bytecode refers to. */
    final Map<String, Set<String>> code = new LinkedHashMap<>();

    private final Object[] pool;
    private final int[] tags;
    private final List<int[]> bootstraps = new ArrayList<>();

    private ClassRefs(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("Not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        pool = new Object[count];
        tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> pool[i] = new int[] { in.readUnsignedShort() };
                case 9, 10, 11, 12, 17, 18 -> pool[i] = new int[] { in.readUnsignedShort(), in.readUnsignedShort() };
                case 15 -> pool[i] = new int[] { in.readUnsignedByte(), in.readUnsignedShort() };
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        name = className(in.readUnsignedShort());
        int superIndex = in.readUnsignedShort();
        if (superIndex != 0) supertypes.add(className(superIndex));
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) supertypes.add(className(in.readUnsignedShort()));
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
        Map<String, byte[]> bodies = new LinkedHashMap<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String method = utf(in.readUnsignedShort()) + ":" + utf(in.readUnsignedShort());
            declared.add(method);
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = utf(in.readUnsignedShort());
                byte[] body = in.readNBytes(in.readInt());
                if (attribute.equals("Code")) bodies.put(method, body);
            }
        }
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String attribute = utf(in.readUnsignedShort());
            byte[] body = in.readNBytes(in.readInt());
            if (attribute.equals("BootstrapMethods")) readBootstraps(body);
        }
        for (int i = 1; i < count; i++) if (tags[i] == 10 || tags[i] == 11) methodRefs.add(member(i));
        for (var body : bodies.entrySet()) code.put(body.getKey(), codeTypes(body.getValue()));
    }

    static ClassRefs read(byte[] bytes) throws IOException {
        return new ClassRefs(bytes);
    }

    /** The types the code of the methods named (any descriptor) refers to, with this class's supertypes. */
    Set<String> touched(Predicate<String> methodName) {
        Set<String> out = new LinkedHashSet<>(supertypes);
        for (var entry : code.entrySet()) if (methodName.test(entry.getKey().substring(0, entry.getKey().indexOf(':')))) out.addAll(entry.getValue());
        return out;
    }

    /** Internal names in a descriptor or a class constant ("[Lx/Y;" and "(Lx/Y;I)V" both give x/Y). */
    static void types(String text, Set<String> out) {
        if (!text.startsWith("[") && !text.startsWith("(") && !text.endsWith(";")) {
            out.add(text);
            return;
        }
        int at = 0;
        while ((at = text.indexOf('L', at)) >= 0) {
            int end = text.indexOf(';', at);
            if (end < 0) break;
            out.add(text.substring(at + 1, end));
            at = end + 1;
        }
    }

    private String utf(int index) {
        return (String) pool[index];
    }

    private String className(int index) {
        return utf(((int[]) pool[index])[0]);
    }

    private String member(int index) {
        int[] ref = (int[]) pool[index];
        int[] nameAndType = (int[]) pool[ref[1]];
        return className(ref[0]) + "." + utf(nameAndType[0]) + ":" + utf(nameAndType[1]);
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.readUnsignedShort();
            in.skipNBytes(in.readInt());
        }
    }

    private void readBootstraps(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int handle = in.readUnsignedShort();
            int arguments = in.readUnsignedShort();
            int[] row = new int[arguments + 1];
            row[0] = handle;
            for (int a = 0; a < arguments; a++) row[a + 1] = in.readUnsignedShort();
            bootstraps.add(row);
        }
    }

    /** The types one constant refers to: a class, a member's owner and descriptor, a method type or handle, a call site. */
    private void constantTypes(int index, Set<String> out) {
        if (index <= 0 || index >= pool.length) return;
        switch (tags[index]) {
            case 7 -> types(className(index), out);
            case 9, 10, 11 -> {
                int[] ref = (int[]) pool[index];
                types(className(ref[0]), out);
                types(utf(((int[]) pool[ref[1]])[1]), out);
            }
            case 16 -> types(utf(((int[]) pool[index])[0]), out);
            case 15 -> constantTypes(((int[]) pool[index])[1], out);
            case 17, 18 -> {
                int[] site = (int[]) pool[index];
                types(utf(((int[]) pool[site[1]])[1]), out);
                if (site[0] < bootstraps.size()) for (int argument : bootstraps.get(site[0])) constantTypes(argument, out);
            }
            default -> {}
        }
    }

    /** Walks a Code attribute's instructions and collects the types of every constant they use. */
    private Set<String> codeTypes(byte[] attribute) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(attribute));
        in.readUnsignedShort();
        in.readUnsignedShort();
        byte[] code = in.readNBytes(in.readInt());
        Set<String> out = new LinkedHashSet<>();
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            switch (op) {
                case 0x12 -> {
                    constantTypes(code[pc + 1] & 0xff, out);
                    pc += 2;
                }
                case 0x13, 0x14, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1 -> {
                    constantTypes(u2(code, pc + 1), out);
                    pc += 3;
                }
                case 0xb9, 0xba -> {
                    constantTypes(u2(code, pc + 1), out);
                    pc += 5;
                }
                case 0xc5 -> {
                    constantTypes(u2(code, pc + 1), out);
                    pc += 4;
                }
                case 0x10, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc -> pc += 2;
                case 0x11, 0x84, 0xc6, 0xc7 -> pc += 3;
                case 0xc8, 0xc9 -> pc += 5;
                case 0xc4 -> pc += (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
                case 0xaa -> {
                    int at = (pc + 4) & ~3;
                    int low = s4(code, at + 4), high = s4(code, at + 8);
                    pc = at + 12 + (high - low + 1) * 4;
                }
                case 0xab -> {
                    int at = (pc + 4) & ~3;
                    pc = at + 8 + s4(code, at + 4) * 8;
                }
                default -> pc += op >= 0x99 && op <= 0xa8 ? 3 : 1;
            }
        }
        return out;
    }

    private static int u2(byte[] code, int at) {
        return ((code[at] & 0xff) << 8) | (code[at + 1] & 0xff);
    }

    private static int s4(byte[] code, int at) {
        return ((code[at] & 0xff) << 24) | ((code[at + 1] & 0xff) << 16) | ((code[at + 2] & 0xff) << 8) | (code[at + 3] & 0xff);
    }
}
