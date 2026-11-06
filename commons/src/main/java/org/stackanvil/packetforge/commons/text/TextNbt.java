package org.stackanvil.packetforge.commons.text;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import net.kyori.adventure.nbt.*;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.stackanvil.packetforge.commons.NbtTag;

/** Preserves NBT tag types across the wire, text serializer, and Adventure's SNBT holders. */
final class TextNbt {
    private TextNbt() {}

    static Object toValue(NbtTag tag) {
        return switch (tag) {
            case NbtTag.ByteValue value -> value.value();
            case NbtTag.ShortValue value -> value.value();
            case NbtTag.IntValue value -> value.value();
            case NbtTag.LongValue value -> value.value();
            case NbtTag.FloatValue value -> value.value();
            case NbtTag.DoubleValue value -> value.value();
            case NbtTag.ByteArray value -> value.value();
            case NbtTag.StringValue value -> value.value();
            case NbtTag.IntArray value -> value.value();
            case NbtTag.LongArray value -> value.value();
            case NbtTag.ListValue value ->
                list(
                        value.elementType(),
                        value.values().stream().map(TextNbt::toValue).toList());
            case NbtTag.Compound value -> {
                var builder = NbtMap.builder();
                value.values().forEach((key, child) -> builder.put(key, toValue(child)));
                yield builder.build();
            }
            case NbtTag.End ignored -> throw new IllegalArgumentException("End tag is not a text value");
        };
    }

    static NbtTag fromValue(Object value) {
        return switch (value) {
            case Boolean number -> new NbtTag.ByteValue((byte) (number ? 1 : 0));
            case Byte number -> new NbtTag.ByteValue(number);
            case Short number -> new NbtTag.ShortValue(number);
            case Integer number -> new NbtTag.IntValue(number);
            case Long number -> new NbtTag.LongValue(number);
            case Float number -> new NbtTag.FloatValue(number);
            case Double number -> new NbtTag.DoubleValue(number);
            case byte[] array -> new NbtTag.ByteArray(array);
            case String text -> new NbtTag.StringValue(text);
            case int[] array -> new NbtTag.IntArray(array);
            case long[] array -> new NbtTag.LongArray(array);
            case NbtList<?> list ->
                new NbtTag.ListValue(
                        list.getType().getId(),
                        list.stream().map(TextNbt::fromValue).toList());
            case NbtMap map -> {
                var tags = new LinkedHashMap<String, NbtTag>();
                map.forEach((key, child) -> tags.put(key, fromValue(child)));
                yield new NbtTag.Compound(tags);
            }
            default -> throw new IllegalArgumentException("Unsupported NBT text value: " + value);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NbtList<?> list(int type, List<Object> values) {
        return new NbtList((NbtType) NbtType.byId(type), values);
    }

    static String toSnbt(Object value) {
        try {
            return TagStringIO.tagStringIO().asString(toBinary(fromValue(value)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode text event NBT", failure);
        }
    }

    static Object fromSnbt(String value) {
        try {
            return toValue(fromBinary(TagStringIO.tagStringIO().asTag(value)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid text event SNBT", failure);
        }
    }

    private static BinaryTag toBinary(NbtTag tag) {
        return switch (tag) {
            case NbtTag.End ignored -> EndBinaryTag.endBinaryTag();
            case NbtTag.ByteValue value -> ByteBinaryTag.byteBinaryTag(value.value());
            case NbtTag.ShortValue value -> ShortBinaryTag.shortBinaryTag(value.value());
            case NbtTag.IntValue value -> IntBinaryTag.intBinaryTag(value.value());
            case NbtTag.LongValue value -> LongBinaryTag.longBinaryTag(value.value());
            case NbtTag.FloatValue value -> FloatBinaryTag.floatBinaryTag(value.value());
            case NbtTag.DoubleValue value -> DoubleBinaryTag.doubleBinaryTag(value.value());
            case NbtTag.ByteArray value -> ByteArrayBinaryTag.byteArrayBinaryTag(value.value());
            case NbtTag.StringValue value -> StringBinaryTag.stringBinaryTag(value.value());
            case NbtTag.IntArray value -> IntArrayBinaryTag.intArrayBinaryTag(value.value());
            case NbtTag.LongArray value -> LongArrayBinaryTag.longArrayBinaryTag(value.value());
            case NbtTag.ListValue value -> {
                var types = List.of(
                        BinaryTagTypes.END,
                        BinaryTagTypes.BYTE,
                        BinaryTagTypes.SHORT,
                        BinaryTagTypes.INT,
                        BinaryTagTypes.LONG,
                        BinaryTagTypes.FLOAT,
                        BinaryTagTypes.DOUBLE,
                        BinaryTagTypes.BYTE_ARRAY,
                        BinaryTagTypes.STRING,
                        BinaryTagTypes.LIST,
                        BinaryTagTypes.COMPOUND,
                        BinaryTagTypes.INT_ARRAY,
                        BinaryTagTypes.LONG_ARRAY);
                yield ListBinaryTag.listBinaryTag(
                        types.get(value.elementType()),
                        value.values().stream().map(TextNbt::toBinary).toList());
            }
            case NbtTag.Compound value -> {
                var builder = CompoundBinaryTag.builder();
                value.values().forEach((key, child) -> builder.put(key, toBinary(child)));
                yield builder.build();
            }
        };
    }

    private static NbtTag fromBinary(BinaryTag tag) {
        return switch (tag) {
            case EndBinaryTag ignored -> NbtTag.End.INSTANCE;
            case ByteBinaryTag value -> new NbtTag.ByteValue(value.value());
            case ShortBinaryTag value -> new NbtTag.ShortValue(value.value());
            case IntBinaryTag value -> new NbtTag.IntValue(value.value());
            case LongBinaryTag value -> new NbtTag.LongValue(value.value());
            case FloatBinaryTag value -> new NbtTag.FloatValue(value.value());
            case DoubleBinaryTag value -> new NbtTag.DoubleValue(value.value());
            case ByteArrayBinaryTag value -> new NbtTag.ByteArray(value.value());
            case StringBinaryTag value -> new NbtTag.StringValue(value.value());
            case IntArrayBinaryTag value -> new NbtTag.IntArray(value.value());
            case LongArrayBinaryTag value -> new NbtTag.LongArray(value.value());
            case ListBinaryTag value -> {
                var homogeneous = value.wrapHeterogeneity();
                yield new NbtTag.ListValue(
                        homogeneous.elementType().id(),
                        homogeneous.stream().map(TextNbt::fromBinary).toList());
            }
            case CompoundBinaryTag value -> {
                var tags = new LinkedHashMap<String, NbtTag>();
                value.forEach(entry -> tags.put(entry.getKey(), fromBinary(entry.getValue())));
                yield new NbtTag.Compound(tags);
            }
            default -> throw new IllegalArgumentException("Unsupported Adventure NBT type: " + tag.type());
        };
    }
}
