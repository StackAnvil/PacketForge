package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiscriminatorCodecsTest {
    @Test
    void dispatchesPayloadsAndRejectsUnknownDiscriminators() {
        var codec =
                WireCodecs.dispatch(
                        WireCodecs.VAR_INT,
                        Map.of(0, WireCodecs.UNIT, 1, WireCodecs.STRING, 2, WireCodecs.INT));
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, new Variant<>(1, "x"));
            assertEquals(new Variant<>(1, "x"), codec.decode(buffer));
            codec.encode(buffer, new Variant<>(0, Unit.INSTANCE));
            assertEquals(0, buffer.readUnsignedByte());
            buffer.clear().writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(buffer, new Variant<>(99, Unit.INSTANCE)));
        } finally {
            buffer.release();
        }
    }

    @Test
    void resolvesRecursiveTablesOnceAndRoundTripsNestedValues() {
        AtomicReference<PacketCodec<Variant<Integer>>> reference = new AtomicReference<>();
        AtomicInteger initialized = new AtomicInteger();
        PacketCodec<Variant<Integer>> codec =
                WireCodecs.lazy(
                        () -> {
                            initialized.incrementAndGet();
                            return WireCodecs.dispatch(
                                    WireCodecs.VAR_INT,
                                    Map.of(0, WireCodecs.UNIT, 1, reference.get()));
                        });
        reference.set(codec);
        assertEquals(0, initialized.get());
        var value = new Variant<>(1, new Variant<>(1, new Variant<>(0, Unit.INSTANCE)));
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, value);
            assertEquals(value, codec.decode(buffer));
            assertEquals(1, initialized.get());
        } finally {
            buffer.release();
        }
    }

    @Test
    void lengthPrefixesBoundPayloadsAndKeepTheFollowingFieldIntact() {
        var codec = WireCodecs.lengthPrefixed(WireCodecs.BYTE, 2);
        var buffer = Unpooled.buffer();
        try {
            buffer.writeByte(2).writeByte(7).writeByte(8).writeByte(9);
            assertEquals((byte) 7, codec.decode(buffer));
            assertEquals(9, buffer.readUnsignedByte());
            buffer.clear().writeByte(3);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
