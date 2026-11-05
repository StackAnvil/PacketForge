package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComponentCodecsTest {
    private static final Map<Integer, PacketCodec<?>> SCHEMAS =
            Map.of(0, WireCodecs.VAR_INT, 1, WireCodecs.STRING, 2, WireCodecs.UNIT);

    @Test
    void encodesAddedAndRemovedComponentsWithOptionalPayloadDelimiters() {
        var value = new ComponentPatch(Map.of(0, 42), Set.of(1));
        for (boolean delimited : new boolean[] {false, true}) {
            var codec = ComponentCodecs.patch(SCHEMAS, delimited);
            var buffer = Unpooled.buffer();
            try {
                codec.encode(buffer, value);
                byte[] actual = new byte[buffer.readableBytes()];
                buffer.getBytes(0, actual);
                assertArrayEquals(
                        delimited ? new byte[] {1, 1, 0, 1, 42, 1} : new byte[] {1, 1, 0, 42, 1},
                        actual);
                assertEquals(value, codec.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsDuplicateUnknownAndConflictingChangesAndBadDelimiters() {
        var codec = ComponentCodecs.patch(SCHEMAS, false);
        for (byte[] body :
                List.of(
                        new byte[] {2, 0, 0, 1, 0, 2},
                        new byte[] {0, 2, 1, 1},
                        new byte[] {1, 0, 3},
                        new byte[] {1, 1, 0, 42, 0},
                        new byte[] {4, 0})) {
            var input = Unpooled.wrappedBuffer(body);
            try {
                assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
            } finally {
                input.release();
            }
        }
        var input = Unpooled.wrappedBuffer(new byte[] {1, 0, 0, 2, 42});
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ComponentCodecs.patch(SCHEMAS, true).decode(input));
        } finally {
            input.release();
        }
    }

    @Test
    void itemStackCoversEmptyRequiredAndNestedComponents() {
        var patch = ComponentCodecs.patch(SCHEMAS, false);
        var optional = ComponentCodecs.itemStack(patch, true);
        var required = ComponentCodecs.itemStack(patch, false);
        var item = new ItemStack(2, 7, new ComponentPatch(Map.of(2, Unit.INSTANCE), Set.of(1)));
        var buffer = Unpooled.buffer();
        try {
            optional.encode(buffer, ItemStack.EMPTY);
            optional.encode(buffer, item);
            assertEquals(ItemStack.EMPTY, optional.decode(buffer));
            assertEquals(item, required.decode(buffer));
            assertFalse(buffer.isReadable());
            assertThrows(
                    IllegalArgumentException.class, () -> required.encode(buffer, ItemStack.EMPTY));
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> required.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void holderSetsPreserveTagsAndDirectIds() {
        for (HolderSet value :
                List.of(
                        new HolderSet.Tag("minecraft:logs"),
                        new HolderSet.Direct(List.of()),
                        new HolderSet.Direct(List.of(0, 128)))) {
            var buffer = Unpooled.buffer();
            try {
                WireCodecs.HOLDER_SET.encode(buffer, value);
                assertEquals(value, WireCodecs.HOLDER_SET.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }
}
