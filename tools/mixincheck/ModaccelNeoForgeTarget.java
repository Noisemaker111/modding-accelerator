import java.io.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;

/**
 * A Java agent for the NeoForge Mixin check. FML's unit-test launch target (the one junit-fml uses)
 * builds the real game layer with Mixin and the mod's configs and opens no window, but it is fixed to
 * the dedicated-server side and then runs NeoForge's own test bootstrap. This agent changes those two
 * things in that one class as it loads, and nothing else:
 *
 *   - "client" side: getDist() answers CLIENT instead of DEDICATED_SERVER, so Mixin selects the
 *     client mixins too and client-only game classes are kept;
 *   - runService(...) does nothing, so the launcher returns as soon as the game layer is built
 *     instead of bootstrapping registries and loading mods.
 *
 * Usage: -javaagent:neoforge-target.jar=client (or =server). Java 8 syntax, no dependencies: the class
 * file is edited in place (a constant renamed, one method body filled with nop and a return), so it
 * works on whichever FML version defines the class, and changes nothing when the class is not there.
 */
public final class ModaccelNeoForgeTarget implements ClassFileTransformer {
    final boolean client;
    ModaccelNeoForgeTarget(boolean client) { this.client = client; }

    public static void premain(String side, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ModaccelNeoForgeTarget(!"server".equals(side)));
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (name == null || !name.startsWith("net/neoforged/fml/loading/targets/JUnit") || name.indexOf('$') >= 0) return null;
        try {
            byte[] patched = patch(bytes, client);
            System.out.println("modaccel: " + name + " now builds the game layer only" + (client ? ", on the client side" : ""));
            return patched;
        } catch (Throwable failure) {
            System.err.println("modaccel: could not adjust " + name + ": " + failure);
            return null;
        }
    }

    /** Rewrites the class file: optionally DEDICATED_SERVER -> CLIENT in the constant pool, and runService's code to nop...return. */
    static byte[] patch(byte[] bytes, boolean client) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length + 16);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(in.readInt()); out.writeShort(in.readUnsignedShort()); out.writeShort(in.readUnsignedShort());
        int count = in.readUnsignedShort(); out.writeShort(count);
        String[] utf8 = new String[count];
        boolean renamed = false;
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte(); out.writeByte(tag);
            switch (tag) {
                case 1: {
                    String text = in.readUTF(); utf8[i] = text;
                    if (client && text.equals("DEDICATED_SERVER")) { text = "CLIENT"; renamed = true; }
                    out.writeUTF(text); break;
                }
                case 7: case 8: case 16: case 19: case 20: out.writeShort(in.readUnsignedShort()); break;
                case 15: out.writeByte(in.readUnsignedByte()); out.writeShort(in.readUnsignedShort()); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: out.writeInt(in.readInt()); break;
                case 5: case 6: out.writeLong(in.readLong()); i++; break;
                default: throw new IOException("unknown constant tag " + tag);
            }
        }
        if (client && !renamed) throw new IOException("no DEDICATED_SERVER constant to change");
        out.writeShort(in.readUnsignedShort()); out.writeShort(in.readUnsignedShort()); out.writeShort(in.readUnsignedShort());
        int interfaces = in.readUnsignedShort(); out.writeShort(interfaces);
        for (int i = 0; i < interfaces; i++) out.writeShort(in.readUnsignedShort());
        int fields = in.readUnsignedShort(); out.writeShort(fields);
        for (int i = 0; i < fields; i++) member(in, out, utf8, false);
        int methods = in.readUnsignedShort(); out.writeShort(methods);
        boolean emptied = false;
        for (int i = 0; i < methods; i++) emptied |= member(in, out, utf8, true);
        if (!emptied) throw new IOException("no runService method to empty");
        byte[] rest = new byte[in.available()]; in.readFully(rest); out.write(rest);
        out.flush();
        return buffer.toByteArray();
    }

    static boolean member(DataInputStream in, DataOutputStream out, String[] utf8, boolean method) throws IOException {
        out.writeShort(in.readUnsignedShort());
        int nameIndex = in.readUnsignedShort(); out.writeShort(nameIndex);
        int descriptorIndex = in.readUnsignedShort(); out.writeShort(descriptorIndex);
        boolean target = method && "runService".equals(utf8[nameIndex]) && "([Ljava/lang/String;Ljava/lang/ModuleLayer;)V".equals(utf8[descriptorIndex]);
        boolean emptied = false;
        int attributes = in.readUnsignedShort(); out.writeShort(attributes);
        for (int a = 0; a < attributes; a++) {
            int attributeName = in.readUnsignedShort(); out.writeShort(attributeName);
            int length = in.readInt(); out.writeInt(length);
            byte[] body = new byte[length]; in.readFully(body);
            if (target && "Code".equals(utf8[attributeName])) {
                // Code: max_stack(2) max_locals(2) code_length(4) code[...] exceptions... attributes...
                int codeLength = ((body[4] & 0xff) << 24) | ((body[5] & 0xff) << 16) | ((body[6] & 0xff) << 8) | (body[7] & 0xff);
                int exceptions = ((body[8 + codeLength] & 0xff) << 8) | (body[9 + codeLength] & 0xff);
                if (exceptions != 0) throw new IOException("runService has an exception table");
                for (int b = 0; b < codeLength - 1; b++) body[8 + b] = 0; // nop
                body[8 + codeLength - 1] = (byte) 0xb1;                   // return
                emptied = true;
            }
            out.write(body);
        }
        return emptied;
    }
}
