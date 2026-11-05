package org.stackanvil.packetforge.commons;

import io.netty.buffer.Unpooled;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SentinelOptionalTest {
    record Payload(int width, String value) {}
    private static final PacketCodec<Payload> PAYLOAD = WireCodecs.of(b -> new Payload(b.readUnsignedByte(), WireCodecs.STRING.decode(b)), (b, v) -> { b.writeByte(v.width()); WireCodecs.STRING.encode(b, v.value()); });
    private static final PacketCodec<Optional<Payload>> CODEC = WireCodecs.sentinelOptional(PAYLOAD, WireCodecs.UNSIGNED_BYTE, 0);
    @Test void reusesTheLeadingByteAndLeavesTheFollowingValueIntact() {
        var buffer = Unpooled.buffer();
        try {
            CODEC.encode(buffer, Optional.empty()); CODEC.encode(buffer, Optional.of(new Payload(3, "x"))); buffer.writeByte(17);
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(0, bytes);
            assertArrayEquals(new byte[]{0, 3, 1, 'x', 17}, bytes);
            assertEquals(Optional.empty(), CODEC.decode(buffer)); assertEquals(Optional.of(new Payload(3, "x")), CODEC.decode(buffer)); assertEquals(17, buffer.readUnsignedByte());
        } finally { buffer.release(); }
    }
    @Test void rejectsPresentPayloadsThatUseTheAbsenceMarker() {
        var buffer = Unpooled.buffer();
        try { assertThrows(IllegalArgumentException.class, () -> CODEC.encode(buffer, Optional.of(new Payload(0, "x")))); assertEquals(0, buffer.writerIndex()); }
        finally { buffer.release(); }
    }
    enum Kind { FIRST, SECOND }
    @Test void namedEnumsUseDeclaredNamesAndTheDeclaredFallback() {
        String[] names = {"first_name", "second_name"}; var codec = WireCodecs.enumNames(Kind.values(), names, WireCodecs.STRING, Kind.SECOND); names[0] = "changed";
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, Kind.FIRST); assertEquals("first_name", WireCodecs.STRING.decode(buffer));
            WireCodecs.STRING.encode(buffer, "unknown"); assertEquals(Kind.SECOND, codec.decode(buffer));
        } finally { buffer.release(); }
    }
}
