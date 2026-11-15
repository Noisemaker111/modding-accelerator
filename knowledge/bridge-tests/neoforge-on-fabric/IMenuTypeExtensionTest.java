package ${package};

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** A menu's opening data: read back as written, and a length the packet does not carry is refused, not allocated. */
class IMenuTypeExtensionTest {

    @Test
    void openingDataRoundTrips() {
        RegistryFriendlyByteBuf data = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }), RegistryAccess.EMPTY);
        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        IMenuTypeExtension.DATA.encode(wire, data);
        RegistryFriendlyByteBuf read = IMenuTypeExtension.DATA.decode(wire);
        byte[] bytes = new byte[read.readableBytes()];
        read.readBytes(bytes);
        assertArrayEquals(new byte[] { 1, 2, 3 }, bytes);
    }

    @Test
    void claimedLengthBeyondThePacketIsRefused() {
        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        wire.writeVarInt(2_000_000_000);
        wire.writeBytes(new byte[] { 1, 2, 3 });
        assertThrows(DecoderException.class, () -> IMenuTypeExtension.DATA.decode(wire));
    }
}
