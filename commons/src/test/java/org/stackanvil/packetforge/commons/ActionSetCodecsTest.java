package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActionSetCodecsTest {
    enum Action {
        A,
        B,
        C
    }

    enum Wide {
        A,
        B,
        C,
        D,
        E,
        F,
        G,
        H,
        I,
        J
    }

    @Test
    void masksUseFixedLittleEndianBitsAndIgnorePaddingOnRead() {
        var codec = ActionSetCodecs.mask(Action.values());
        var input = Unpooled.buffer();
        var output = Unpooled.buffer();
        try {
            input.writeByte(0xfd);
            assertEquals(Set.of(Action.A, Action.C), codec.decode(input));
            codec.encode(output, Set.of(Action.C, Action.A));
            assertEquals(5, output.readUnsignedByte());
            ActionSetCodecs.mask(Wide.values()).encode(output, Set.of(Wide.A, Wide.J));
            assertEquals(1, output.readUnsignedByte());
            assertEquals(2, output.readUnsignedByte());
            assertFalse(output.isReadable());
            assertFalse(input.isReadable());
        } finally {
            input.release();
            output.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSetCodecs.mask(new Action[] {Action.B, Action.A}));
    }

    @Test
    void entriesFollowOrdinalOrderRegardlessOfMapOrderAndIgnoreUnselectedValues() {
        var schemas =
                Map.<Action, PacketCodec<?>>of(
                        Action.A,
                        WireCodecs.VAR_INT,
                        Action.B,
                        WireCodecs.BOOL,
                        Action.C,
                        WireCodecs.STRING);
        UUID id = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
        var entry = new ActionEntry<>(id, Map.of(Action.C, "café", Action.A, 128, Action.B, true));
        var codec = ActionSetCodecs.entry(Set.of(Action.C, Action.A), schemas);
        var bytes = Unpooled.buffer();
        try {
            codec.encode(bytes, entry);
            assertEquals(id, WireCodecs.UUID_CODEC.decode(bytes));
            assertEquals(128, WireCodecs.VAR_INT.decode(bytes));
            assertEquals("café", WireCodecs.STRING.decode(bytes));
            assertFalse(bytes.isReadable());
            bytes.clear();
            codec.encode(bytes, entry);
            assertEquals(
                    new ActionEntry<>(id, Map.of(Action.A, 128, Action.C, "café")),
                    codec.decode(bytes));
            assertFalse(bytes.isReadable());
            bytes.clear();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(bytes, new ActionEntry<>(id, Map.of(Action.A, 1))));
            assertFalse(bytes.isReadable());
            bytes.writeZero(15);
            assertThrows(IndexOutOfBoundsException.class, () -> codec.decode(bytes));
        } finally {
            bytes.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ActionSetCodecs.entry(
                                Set.of(Action.B), Map.of(Action.A, WireCodecs.VAR_INT)));
    }
}
