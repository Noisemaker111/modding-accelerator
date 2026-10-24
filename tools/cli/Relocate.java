import java.io.*;

/** Relocate the agent's private ASM copy without loading or changing the game's ASM. */
final class Relocate {
    static String name(String name) { return name.replace("org/objectweb/asm", "dev/modaccel/internal/asm"); }
    static byte[] classes(byte[] bytes) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bytes)); var buffer = new ByteArrayOutputStream(); var output = new DataOutputStream(buffer);
        if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class in agent");
        output.writeInt(0xCAFEBABE); output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort());
        int count = input.readUnsignedShort(); output.writeShort(count);
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte(); output.writeByte(tag);
            switch (tag) {
                case 1 -> output.writeUTF(name(input.readUTF()).replace("org.objectweb.asm", "dev.modaccel.internal.asm"));
                case 3, 4, 9, 10, 11, 12, 17, 18 -> output.writeInt(input.readInt());
                case 5, 6 -> { output.writeLong(input.readLong()); i++; }
                case 7, 8, 16, 19, 20 -> output.writeShort(input.readUnsignedShort());
                case 15 -> { output.writeByte(input.readUnsignedByte()); output.writeShort(input.readUnsignedShort()); }
                default -> throw new IOException("Unknown class constant " + tag);
            }
        }
        input.transferTo(output); return buffer.toByteArray();
    }
}
