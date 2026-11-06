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

    sealed interface Value extends TypedPayload<Action> permits NumberValue, FlagValue, TextValue {}

    record NumberValue(int value) implements Value {
        public Action type() {
            return Action.A;
        }
    }

    record FlagValue(boolean value) implements Value {
        public Action type() {
            return Action.B;
        }
    }

    record TextValue(String value) implements Value {
        public Action type() {
            return Action.C;
        }
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
    void rejectsAnActionKeyThatDoesNotMatchItsPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActionEntry<>(new UUID(1, 2), Map.of(Action.A, new TextValue("wrong action"))));
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
        assertThrows(IllegalArgumentException.class, () -> ActionSetCodecs.mask(new Action[] {Action.B, Action.A}));
    }

    @Test
    void entriesFollowOrdinalOrderRegardlessOfMapOrderAndIgnoreUnselectedValues() {
        var schemas = Map.<Action, PacketCodec<Value>>of(
                Action.A, WireCodecs.mapped(WireCodecs.VAR_INT, NumberValue::new, v -> ((NumberValue) v).value()),
                Action.B, WireCodecs.mapped(WireCodecs.BOOL, FlagValue::new, v -> ((FlagValue) v).value()),
                Action.C, WireCodecs.mapped(WireCodecs.STRING, TextValue::new, v -> ((TextValue) v).value()));
        UUID id = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
        var entry = new ActionEntry<>(
                id,
                Map.<Action, Value>of(
                        Action.C,
                        new TextValue("café"),
                        Action.A,
                        new NumberValue(128),
                        Action.B,
                        new FlagValue(true)));
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
                    new ActionEntry<>(
                            id, Map.<Action, Value>of(Action.A, new NumberValue(128), Action.C, new TextValue("café"))),
                    codec.decode(bytes));
            assertFalse(bytes.isReadable());
            bytes.clear();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(
                            bytes, new ActionEntry<>(id, Map.<Action, Value>of(Action.A, new NumberValue(1)))));
            assertFalse(bytes.isReadable());
            bytes.writeZero(15);
            assertThrows(IndexOutOfBoundsException.class, () -> codec.decode(bytes));
        } finally {
            bytes.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSetCodecs.entry(Set.of(Action.B), Map.of(Action.A, schemas.get(Action.A))));
    }
}
