package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class ComponentCodecsTest {
    private static final DataComponentType<Integer> NUMBER =
            new DataComponentType<>(0, Key.key("test:number"), WireCodecs.VAR_INT);
    private static final DataComponentType<String> TEXT =
            new DataComponentType<>(1, Key.key("test:text"), WireCodecs.STRING);
    private static final DataComponentType<Unit> FLAG =
            new DataComponentType<>(2, Key.key("test:flag"), WireCodecs.UNIT);
    private static final List<DataComponentType<?>> SCHEMAS = List.of(NUMBER, TEXT, FLAG);

    @Test
    void standaloneComponentsRetainTypedKeysAndRejectUnknownOrForeignIdentities() {
        var codec = ComponentCodecs.component(SCHEMAS);
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, NUMBER.withValue(42));
            codec.encode(buffer, TEXT.withValue("text"));
            assertEquals(NUMBER.withValue(42), codec.decode(buffer));
            assertEquals(TEXT.withValue("text"), codec.decode(buffer));
            assertFalse(buffer.isReadable());
            var foreign = new DataComponentType<>(0, Key.key("test:foreign"), WireCodecs.STRING);
            assertThrows(IllegalArgumentException.class, () -> codec.encode(buffer, foreign.withValue("wrong codec")));
            assertFalse(buffer.isReadable());
            buffer.writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.component(List.of(NUMBER, NUMBER)));
    }

    @Test
    void encodesAddedAndRemovedComponentsWithOptionalPayloadDelimiters() {
        var value = ComponentPatch.builder().set(NUMBER, 42).remove(TEXT).build();
        for (boolean delimited : new boolean[] {false, true}) {
            var codec = ComponentCodecs.patch(SCHEMAS, delimited);
            var buffer = Unpooled.buffer();
            try {
                codec.encode(buffer, value);
                byte[] actual = new byte[buffer.readableBytes()];
                buffer.getBytes(0, actual);
                assertArrayEquals(delimited ? new byte[] {1, 1, 0, 1, 42, 1} : new byte[] {1, 1, 0, 42, 1}, actual);
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
        for (byte[] body : List.of(
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
        var item = new ItemStack(
                2,
                7,
                ComponentPatch.builder().set(FLAG, Unit.INSTANCE).remove(TEXT).build());
        var buffer = Unpooled.buffer();
        try {
            optional.encode(buffer, ItemStack.EMPTY);
            optional.encode(buffer, item);
            assertEquals(ItemStack.EMPTY, optional.decode(buffer));
            assertEquals(item, required.decode(buffer));
            assertFalse(buffer.isReadable());
            assertThrows(IllegalArgumentException.class, () -> required.encode(buffer, ItemStack.EMPTY));
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> required.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsForeignKeysAndInvalidSchemaTables() {
        var foreign = new DataComponentType<>(0, Key.key("test:foreign"), WireCodecs.STRING);
        var codec = ComponentCodecs.patch(SCHEMAS, false);
        var buffer = Unpooled.buffer();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(buffer, ComponentPatch.of(foreign.withValue("wrong codec"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(
                            buffer, ComponentPatch.builder().remove(foreign).build()));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.patch(List.of(NUMBER, foreign), false));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.patch(List.of(TEXT), false));
    }

    @Test
    void holderSetsPreserveTagsAndDirectIds() {
        for (HolderSet value : List.of(
                new HolderSet.Tag(Key.key("minecraft:logs")),
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
