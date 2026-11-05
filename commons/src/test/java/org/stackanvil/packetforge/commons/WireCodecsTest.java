package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireCodecsTest {
    @Test void matchesKnownVariableIntegerVectors() {
        int[] values = {0, 1, 127, 128, 255, 2147483647, -1, Integer.MIN_VALUE};
        String[] hex = {"00", "01", "7f", "8001", "ff01", "ffffffff07", "ffffffff0f", "8080808008"};
        for (int i = 0; i < values.length; i++) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                WireCodecs.VAR_INT.encode(buffer, values[i]);
                byte[] data = new byte[buffer.readableBytes()]; buffer.getBytes(0, data);
                assertEquals(hex[i], HexFormat.of().formatHex(data));
                assertEquals(values[i], WireCodecs.VAR_INT.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
        roundTrip(WireCodecs.VAR_LONG, Long.MIN_VALUE);
        roundTrip(WireCodecs.VAR_LONG, -1L);
        roundTrip(WireCodecs.VAR_LONG, Long.MAX_VALUE);
    }
    @Test void rejectsMalformedAndOversizedPayloads() {
        withBuffer(new byte[] {-128, -128, -128, -128, -128, 0}, b -> assertThrows(IllegalArgumentException.class, () -> WireCodecs.VAR_INT.decode(b)));
        withBuffer(new byte[] {2, (byte) 0xc0, (byte) 0x80}, b -> assertThrows(IllegalArgumentException.class, () -> WireCodecs.STRING.decode(b)));
        withBuffer(new byte[] {127}, b -> assertThrows(IllegalArgumentException.class, () -> WireCodecs.byteArray(4).decode(b)));
        withBuffer(new byte[] {1, 65}, b -> assertThrows(IllegalArgumentException.class, () -> WireCodecs.string(0).decode(b)));
        ByteBuf buffer = Unpooled.buffer();
        try { assertThrows(IllegalArgumentException.class, () -> WireCodecs.string(1).encode(buffer, "xx")); }
        finally { buffer.release(); }
    }
    @Test void preservesStringsContainersAndPackedPositions() {
        roundTrip(WireCodecs.string(4), "\ud83d\ude03é");
        roundTrip(WireCodecs.IDENTIFIER, "minecraft:stone");
        roundTrip(WireCodecs.optional(WireCodecs.VAR_INT), Optional.of(-1));
        roundTrip(WireCodecs.optional(WireCodecs.VAR_INT), Optional.empty());
        roundTrip(WireCodecs.list(WireCodecs.STRING, 3), List.of("a", "b"));
        roundTrip(WireCodecs.BLOCK_POSITION, new BlockPosition(-33554432, -2048, 33554431));
        roundTrip(WireCodecs.BLOCK_POSITION, new BlockPosition(33554431, 2047, -33554432));
        withBuffer(new byte[] {5, 115, 116, 111, 110, 101}, b -> assertEquals("minecraft:stone", WireCodecs.IDENTIFIER.decode(b)));
    }
    private static <T> void roundTrip(PacketCodec<T> codec, T value) {
        ByteBuf b = Unpooled.buffer();
        try { codec.encode(b, value); assertEquals(value, codec.decode(b)); assertFalse(b.isReadable()); }
        finally { b.release(); }
    }
    private static void withBuffer(byte[] data, java.util.function.Consumer<ByteBuf> test) {
        ByteBuf b = Unpooled.wrappedBuffer(data); try { test.accept(b); } finally { b.release(); }
    }
}
