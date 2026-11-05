package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataCodecsTest {
    private static final PacketCodec<List<MetadataEntry>> CODEC = MetadataCodecs.entries(
            WireCodecs.dispatch(WireCodecs.VAR_INT, Map.of(0, WireCodecs.BYTE, 1, WireCodecs.STRING)));

    @Test
    void sentinelOnlyTerminatesTheIndexAndPreservesFollowingBytes() {
        var values = List.of(
                new MetadataEntry(254, new Variant<>(0, (byte) -1)), new MetadataEntry(0, new Variant<>(1, "x")));
        var buffer = Unpooled.buffer();
        try {
            CODEC.encode(buffer, values);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            assertArrayEquals(new byte[] {-2, 0, -1, 0, 1, 1, 'x', -1}, bytes);
            buffer.writeByte(17);
            assertEquals(values, CODEC.decode(buffer));
            assertEquals(17, buffer.readUnsignedByte());
            CODEC.encode(buffer, List.of());
            assertEquals(List.of(), CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsMissingSentinelsUnknownSerializersAndDuplicateIndices() {
        for (byte[] bytes : List.of(new byte[] {0, 0, 1}, new byte[] {0, 2, -1}, new byte[] {0, 0, 1, 0, 0, 2, -1})) {
            var input = Unpooled.wrappedBuffer(bytes);
            try {
                assertThrows(RuntimeException.class, () -> CODEC.decode(input));
            } finally {
                input.release();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new MetadataEntry(255, new Variant<>(0, (byte) 0)));
    }
}
