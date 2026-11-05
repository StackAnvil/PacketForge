package org.stackanvil.packetforge.commons;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionCodecsTest {
    @Test void preservesBothUnionBranchesAndCollectionContents() {
        roundTrip(WireCodecs.either(WireCodecs.INT, WireCodecs.STRING), Either.left(37));
        roundTrip(WireCodecs.either(WireCodecs.INT, WireCodecs.STRING), Either.right("text"));
        roundTrip(WireCodecs.map(WireCodecs.STRING, WireCodecs.INT, 2), Map.of("a", -1, "b", 128));
        roundTrip(WireCodecs.set(WireCodecs.STRING, 2), Set.of("a", "b"));
        roundTrip(WireCodecs.fixedList(WireCodecs.INT, 2), List.of(1, -1));
        roundTrip(WireCodecs.CHUNK_POSITION, new ChunkPosition(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
    @Test void rejectsWrongCollectionSizes() {
        var buffer = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> WireCodecs.fixedList(WireCodecs.INT, 2).encode(buffer, List.of(1)));
            assertThrows(IllegalArgumentException.class, () -> WireCodecs.map(WireCodecs.STRING, WireCodecs.INT, 0).encode(buffer, Map.of("a", 1)));
            buffer.writeByte(3);
            assertThrows(IllegalArgumentException.class, () -> WireCodecs.map(WireCodecs.STRING, WireCodecs.INT, 2).decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void preservesEnumIdFallbackPolicies() {
        for (var policy : WireCodecs.EnumFallback.values()) {
            var buffer = Unpooled.buffer();
            try {
                WireCodecs.VAR_INT.encode(buffer, -1);
                var codec = WireCodecs.enumIds(Example.values(), new int[]{1, 2, 0}, WireCodecs.VAR_INT, policy);
                if (policy == WireCodecs.EnumFallback.STRICT) assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
                else assertEquals(policy == WireCodecs.EnumFallback.WRAP ? Example.B : Example.C, codec.decode(buffer));
            } finally { buffer.release(); }
        }
    }
    enum Example { A, B, C }
    private static <T> void roundTrip(PacketCodec<T> codec, T expected) {
        var buffer = Unpooled.buffer();
        try { codec.encode(buffer, expected); assertEquals(expected, codec.decode(buffer)); assertFalse(buffer.isReadable()); }
        finally { buffer.release(); }
    }
}
