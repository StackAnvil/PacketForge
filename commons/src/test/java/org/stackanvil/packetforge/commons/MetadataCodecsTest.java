package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataCodecsTest {
    sealed interface Value extends TypedPayload<Integer> permits ByteValue, TextValue {}

    record ByteValue(byte value) implements Value {
        public Integer type() {
            return 0;
        }
    }

    record TextValue(String value) implements Value {
        public Integer type() {
            return 1;
        }
    }

    private static final PacketCodec<List<MetadataEntry<Value>>> CODEC = MetadataCodecs.entries(WireCodecs.dispatch(
            WireCodecs.VAR_INT,
            Map.<Integer, PacketCodec<Value>>of(
                    0,
                    WireCodecs.mapped(WireCodecs.BYTE, ByteValue::new, v -> ((ByteValue) v).value()),
                    1,
                    WireCodecs.mapped(WireCodecs.STRING, TextValue::new, v -> ((TextValue) v).value()))));

    @Test
    void sentinelOnlyTerminatesTheIndexAndPreservesFollowingBytes() {
        var values = List.of(
                new MetadataEntry<Value>(254, new ByteValue((byte) -1)),
                new MetadataEntry<Value>(0, new TextValue("x")));
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
        assertThrows(IllegalArgumentException.class, () -> new MetadataEntry<>(255, new ByteValue((byte) 0)));
    }
}
