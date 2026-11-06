package org.stackanvil.packetforge.commons.text;

import com.google.gson.*;
import java.util.Arrays;
import org.stackanvil.packetforge.commons.NbtTag;

/** Conversion for opaque event data. Text and style fields use Adventure's serializers. */
final class TextJson {
    private TextJson() {}

    static NbtTag toNbt(JsonElement value) {
        if (value instanceof JsonObject object) {
            var tags = new java.util.LinkedHashMap<String, NbtTag>();
            object.entrySet().forEach(entry -> tags.put(entry.getKey(), toNbt(entry.getValue())));
            return new NbtTag.Compound(tags);
        }
        if (value instanceof JsonArray array) {
            var values = new java.util.ArrayList<NbtTag>();
            array.forEach(element -> values.add(toNbt(element)));
            if (!values.isEmpty() && values.stream().allMatch(NbtTag.ByteValue.class::isInstance)) {
                byte[] bytes = new byte[values.size()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = ((NbtTag.ByteValue) values.get(i)).value();
                return new NbtTag.ByteArray(bytes);
            }
            if (!values.isEmpty() && values.stream().allMatch(NbtTag.IntValue.class::isInstance))
                return new NbtTag.IntArray(values.stream()
                        .mapToInt(tag -> ((NbtTag.IntValue) tag).value())
                        .toArray());
            if (!values.isEmpty() && values.stream().allMatch(NbtTag.LongValue.class::isInstance))
                return new NbtTag.LongArray(values.stream()
                        .mapToLong(tag -> ((NbtTag.LongValue) tag).value())
                        .toArray());
            var list = new HeterogeneousNbtList();
            values.forEach(element -> list.add(TextNbt.toValue(element)));
            return TextNbt.fromValue(list.build());
        }
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isString()) return new NbtTag.StringValue(primitive.getAsString());
            if (primitive.isBoolean()) return new NbtTag.ByteValue((byte) (primitive.getAsBoolean() ? 1 : 0));
            var number = primitive.getAsBigDecimal();
            try {
                long integer = number.longValueExact();
                if (integer >= Byte.MIN_VALUE && integer <= Byte.MAX_VALUE) return new NbtTag.ByteValue((byte) integer);
                if (integer >= Short.MIN_VALUE && integer <= Short.MAX_VALUE)
                    return new NbtTag.ShortValue((short) integer);
                if (integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE)
                    return new NbtTag.IntValue((int) integer);
                return new NbtTag.LongValue(integer);
            } catch (ArithmeticException fractionalOrLarge) {
                double decimal = number.doubleValue();
                return (double) (float) decimal == decimal
                        ? new NbtTag.FloatValue((float) decimal)
                        : new NbtTag.DoubleValue(decimal);
            }
        }
        throw new IllegalArgumentException("Null is not an NBT event value");
    }

    static JsonElement fromNbt(NbtTag value) {
        return switch (value) {
            case NbtTag.ByteValue number -> new JsonPrimitive(number.value());
            case NbtTag.ShortValue number -> new JsonPrimitive(number.value());
            case NbtTag.IntValue number -> new JsonPrimitive(number.value());
            case NbtTag.LongValue number -> new JsonPrimitive(number.value());
            case NbtTag.FloatValue number -> new JsonPrimitive(number.value());
            case NbtTag.DoubleValue number -> new JsonPrimitive(number.value());
            case NbtTag.StringValue text -> new JsonPrimitive(text.value());
            case NbtTag.ByteArray array -> {
                var json = new JsonArray();
                for (byte number : array.value()) json.add(number);
                yield json;
            }
            case NbtTag.IntArray array -> {
                var json = new JsonArray();
                Arrays.stream(array.value()).forEach(json::add);
                yield json;
            }
            case NbtTag.LongArray array -> {
                var json = new JsonArray();
                Arrays.stream(array.value()).forEach(json::add);
                yield json;
            }
            case NbtTag.ListValue list -> {
                var json = new JsonArray();
                for (var element : list.values()) {
                    // NbtOps uses these wrappers when a list contains mixed tag types.
                    if (element instanceof NbtTag.Compound map
                            && map.values().size() == 1
                            && map.values().containsKey(""))
                        element = map.values().get("");
                    json.add(fromNbt(element));
                }
                yield json;
            }
            case NbtTag.Compound map -> {
                var json = new JsonObject();
                map.values().forEach((key, child) -> json.add(key, fromNbt(child)));
                yield json;
            }
            case NbtTag.End ignored -> throw new IllegalArgumentException("End tag is not a JSON event value");
        };
    }
}
