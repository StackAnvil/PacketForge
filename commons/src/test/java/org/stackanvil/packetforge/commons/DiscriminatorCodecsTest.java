package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiscriminatorCodecsTest {
    sealed interface Value extends TypedPayload<Integer> permits Stop, Text, Nested, Unknown {}

    record Stop() implements Value {
        public Integer type() {
            return 0;
        }
    }

    record Text(String value) implements Value {
        public Integer type() {
            return 1;
        }
    }

    record Nested(Value value) implements Value {
        public Integer type() {
            return 1;
        }
    }

    record Unknown() implements Value {
        public Integer type() {
            return 99;
        }
    }

    @Test
    void dispatchesPayloadsAndRejectsUnknownDiscriminators() {
        var codec = WireCodecs.dispatch(
                WireCodecs.VAR_INT,
                Map.<Integer, PacketCodec<Value>>of(
                        0,
                        WireCodecs.mapped(WireCodecs.UNIT, ignored -> new Stop(), ignored -> Unit.INSTANCE),
                        1,
                        WireCodecs.mapped(WireCodecs.STRING, Text::new, v -> ((Text) v).value())));
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, new Text("x"));
            assertEquals(new Text("x"), codec.decode(buffer));
            codec.encode(buffer, new Stop());
            assertEquals(0, buffer.readUnsignedByte());
            buffer.clear().writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> codec.encode(buffer, new Unknown()));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsAPayloadDecodedByTheWrongDiscriminatorSchema() {
        var codec = WireCodecs.dispatch(
                WireCodecs.VAR_INT,
                Map.<Integer, PacketCodec<Value>>of(
                        0, WireCodecs.mapped(WireCodecs.STRING, Text::new, value -> ((Text) value).value())));
        var input = Unpooled.wrappedBuffer(new byte[] {0, 1, 'x'});
        try {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
        } finally {
            input.release();
        }
    }

    @Test
    void resolvesRecursiveTablesOnceAndRoundTripsNestedValues() {
        AtomicReference<PacketCodec<Value>> reference = new AtomicReference<>();
        AtomicInteger initialized = new AtomicInteger();
        PacketCodec<Value> codec = WireCodecs.lazy(() -> {
            initialized.incrementAndGet();
            return WireCodecs.dispatch(
                    WireCodecs.VAR_INT,
                    Map.<Integer, PacketCodec<Value>>of(
                            0,
                            WireCodecs.mapped(WireCodecs.UNIT, ignored -> new Stop(), ignored -> Unit.INSTANCE),
                            1,
                            WireCodecs.mapped(reference.get(), Nested::new, v -> ((Nested) v).value())));
        });
        reference.set(codec);
        assertEquals(0, initialized.get());
        var value = new Nested(new Nested(new Stop()));
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
