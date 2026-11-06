package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.protocol.packet.game.*;

class ImperativePacketTest {
    @Test
    void lowersBitFieldsAndNormalizesUnusedBits() {
        var input = Unpooled.wrappedBuffer(new byte[] {(byte) 255});
        var output = Unpooled.buffer();
        try {
            var packet = ServerboundPlayerAbilitiesPacket.CODEC.decode(input);
            assertTrue(packet.isFlying());
            packet.serialize(output);
            assertEquals(2, output.readUnsignedByte());
        } finally {
            input.release();
            output.release();
        }
    }

    @Test
    void omitsConditionalFieldsWhenTheDiscriminatorChanges() {
        var input = Unpooled.wrappedBuffer(new byte[] {0, 1, 'x'});
        var output = Unpooled.buffer();
        try {
            var opened = ServerboundSeenAdvancementsPacket.CODEC.decode(input);
            assertEquals(ServerboundSeenAdvancementsPacket_Action.OPENED_TAB, opened.action());
            assertEquals(Key.key("minecraft:x"), opened.tab());
            var closed = new ServerboundSeenAdvancementsPacket(
                    ServerboundSeenAdvancementsPacket_Action.CLOSED_SCREEN, Key.key("minecraft:unused"));
            closed.serialize(output);
            assertEquals(1, output.readableBytes());
            assertEquals(1, output.readUnsignedByte());
        } finally {
            input.release();
            output.release();
        }
    }
}
