package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbtCodecsTest {
    @Test
    void roundTripsEveryTagKindAndNestedContainers() {
        List<NbtTag> values =
                List.of(
                        NbtTag.End.INSTANCE,
                        new NbtTag.ByteValue((byte) -1),
                        new NbtTag.ShortValue((short) -123),
                        new NbtTag.IntValue(Integer.MIN_VALUE),
                        new NbtTag.LongValue(Long.MAX_VALUE),
                        new NbtTag.FloatValue(1.25f),
                        new NbtTag.DoubleValue(-4.75),
                        new NbtTag.ByteArray(new byte[] {1, -1}),
                        new NbtTag.StringValue("a\0é😃"),
                        new NbtTag.ListValue(
                                3, List.of(new NbtTag.IntValue(1), new NbtTag.IntValue(2))),
                        new NbtTag.Compound(
                                Map.of(
                                        "nested",
                                        new NbtTag.Compound(
                                                Map.of("name", new NbtTag.StringValue("x"))))),
                        new NbtTag.IntArray(new int[] {Integer.MIN_VALUE, 37}),
                        new NbtTag.LongArray(new long[] {Long.MIN_VALUE, 42}));
        var codec = NbtCodecs.tag(1_000_000, 16, true);
        for (NbtTag tag : values) {
            var first = Unpooled.buffer();
            var second = Unpooled.buffer();
            try {
                codec.encode(first, tag);
                byte[] expected = new byte[first.readableBytes()];
                first.getBytes(0, expected);
                codec.encode(second, codec.decode(first));
                byte[] actual = new byte[second.readableBytes()];
                second.readBytes(actual);
                assertArrayEquals(expected, actual);
                assertFalse(first.isReadable());
            } finally {
                first.release();
                second.release();
            }
        }
    }

    @Test
    void usesModifiedUtf8AndOmitsTheRootName() {
        var buffer = Unpooled.buffer();
        try {
            NbtCodecs.TAG.encode(buffer, new NbtTag.StringValue("a\0é😃"));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            assertEquals("08000b61c080c3a9eda0bdedb883", HexFormat.of().formatHex(bytes));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsMalformedTypesLengthsDepthAndBudgets() {
        for (byte[] bytes :
                new byte[][] {
                    {99}, {7, -1, -1, -1, -1}, {9, 0, 0, 0, 0, 1, 0}, {12, 127, -1, -1, -1}
                }) {
            var buffer = Unpooled.wrappedBuffer(bytes);
            try {
                assertThrows(IllegalArgumentException.class, () -> NbtCodecs.TAG.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        var buffer = Unpooled.buffer();
        try {
            var nested =
                    new NbtTag.Compound(
                            Map.of(
                                    "child",
                                    new NbtTag.Compound(
                                            Map.of("child", new NbtTag.Compound(Map.of())))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NbtCodecs.tag(1024, 1, false).encode(buffer, nested));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NbtCodecs.tag(4, 16, false)
                                    .encode(buffer, new NbtTag.StringValue("long string")));
        } finally {
            buffer.release();
        }
    }
}
