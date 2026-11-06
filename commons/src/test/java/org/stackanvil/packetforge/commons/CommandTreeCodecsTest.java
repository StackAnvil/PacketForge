package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class CommandTreeCodecsTest {
    record ArgumentValue(int value) implements TypedPayload<Integer> {
        public Integer type() {
            return 123;
        }
    }

    @Test
    void nodesRetainFlagsReferencesAndTypedPayloads() {
        var codec = CommandTreeCodecs.node(
                Map.of(123, WireCodecs.mapped(WireCodecs.INT, ArgumentValue::new, ArgumentValue::value)));
        for (var node : List.of(
                new CommandNode<ArgumentValue>(0, new int[] {1, 2}, 0, null),
                new CommandNode<ArgumentValue>(13, new int[0], 123, new CommandNode.Literal<ArgumentValue>("café")),
                new CommandNode<ArgumentValue>(
                        50,
                        new int[] {0},
                        0,
                        new CommandNode.Argument<ArgumentValue>(
                                "argument", new ArgumentValue(Integer.MIN_VALUE), Key.key("minecraft:ask_server"))))) {
            var buffer = Unpooled.buffer();
            try {
                codec.encode(buffer, node);
                var decoded = codec.decode(buffer);
                assertEquals(node.flags(), decoded.flags());
                assertArrayEquals(node.children(), decoded.children());
                assertEquals(node.redirect(), decoded.redirect());
                assertEquals(node.stub(), decoded.stub());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsAnArgumentDecodedByTheWrongSchema() {
        var codec = CommandTreeCodecs.node(
                Map.of(1, WireCodecs.mapped(WireCodecs.INT, ArgumentValue::new, ArgumentValue::value)));
        var input = Unpooled.wrappedBuffer(new byte[] {2, 0, 1, 'x', 1, 0, 0, 0, 0});
        try {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
        } finally {
            input.release();
        }
    }

    @Test
    void unknownArgumentIdsStopBeforeSuggestionsAndNormalizeToAnAbsentStub() {
        var codec = CommandTreeCodecs.node(Map.of());
        var input = Unpooled.buffer();
        var output = Unpooled.buffer();
        try {
            input.writeByte(18).writeByte(0);
            WireCodecs.STRING.encode(input, "unknown");
            WireCodecs.VAR_INT.encode(input, 123);
            input.writeByte(99);
            var node = codec.decode(input);
            assertNull(node.stub());
            assertEquals(99, input.readUnsignedByte());
            codec.encode(output, node);
            assertArrayEquals(new byte[] {18, 0}, new byte[] {output.readByte(), output.readByte()});
            assertFalse(output.isReadable());
        } finally {
            input.release();
            output.release();
        }
    }

    @Test
    void validatesChildAndRedirectGraphsSeparatelyAndIgnoresReferencesOutsideTheNodeSet() {
        CommandTreeCodecs.validate(List.of(
                new CommandNode<ArgumentValue>(0, new int[] {1, 1}, 0, null),
                new CommandNode<ArgumentValue>(8, new int[] {-1, 9}, 0, null)));
        assertThrows(
                IllegalStateException.class,
                () -> CommandTreeCodecs.validate(List.of(new CommandNode<ArgumentValue>(0, new int[] {0}, 0, null))));
        assertThrows(
                IllegalStateException.class,
                () -> CommandTreeCodecs.validate(List.of(new CommandNode<ArgumentValue>(8, new int[0], 0, null))));
        assertThrows(
                IllegalStateException.class,
                () -> CommandTreeCodecs.validate(List.of(
                        new CommandNode<ArgumentValue>(0, new int[] {1}, 0, null),
                        new CommandNode<ArgumentValue>(0, new int[] {0}, 0, null))));
        CommandTreeCodecs.validate(List.of());
    }
}
