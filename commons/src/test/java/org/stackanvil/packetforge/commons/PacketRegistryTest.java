package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;
import static org.stackanvil.packetforge.commons.PacketRegistry.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketRegistryTest {
    record NumberPacket(int value) implements Packet {
        public void serialize(ByteBuf b) {
            WireCodecs.VAR_INT.encode(b, value);
        }

        static final PacketCodec<NumberPacket> CODEC =
                WireCodecs.of(b -> new NumberPacket(WireCodecs.VAR_INT.decode(b)), (b, p) -> p.serialize(b));
    }

    final Key key = new Key(State.PLAY, Flow.CLIENTBOUND, 17);
    final Entry<NumberPacket> entry = new Entry<>(key, "test:number", NumberPacket.class, NumberPacket.CODEC);
    final PacketRegistry registry = new PacketRegistry(List.of(entry));

    @Test
    void roundTripsIdAndBodyInTheCorrectDirection() {
        ByteBuf b = Unpooled.buffer();
        try {
            registry.encode(key, new NumberPacket(-1), b);
            assertEquals(new NumberPacket(-1), registry.decode(State.PLAY, Flow.CLIENTBOUND, b));
            assertFalse(b.isReadable());
            b.clear();
            registry.encode(key, new NumberPacket(1), b);
            assertThrows(IllegalArgumentException.class, () -> registry.decode(State.PLAY, Flow.SERVERBOUND, b));
            assertEquals(0, b.readerIndex());
        } finally {
            b.release();
        }
    }

    @Test
    void rejectsTrailingAndTruncatedBodiesWithoutConsumingThem() {
        ByteBuf b = Unpooled.buffer();
        try {
            b.writeByte(17).writeByte(1).writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> registry.decode(State.PLAY, Flow.CLIENTBOUND, b));
            assertEquals(0, b.readerIndex());
            b.clear().writeByte(17).writeByte(128);
            assertThrows(IndexOutOfBoundsException.class, () -> registry.decode(State.PLAY, Flow.CLIENTBOUND, b));
            assertEquals(0, b.readerIndex());
        } finally {
            b.release();
        }
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new PacketRegistry(List.of(entry, entry)));
    }
}
