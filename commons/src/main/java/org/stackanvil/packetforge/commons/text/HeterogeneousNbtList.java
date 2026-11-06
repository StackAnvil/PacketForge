/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

/** NBT lists require one tag type. Mixed lists wrap values in compounds with an empty key. */
final class HeterogeneousNbtList {
    private final List<NbtType<?>> types = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();

    public static List<Object> getList(NbtMap map, String key) {
        Object list = map.get(key);
        if (list instanceof NbtList<?> nbtList) {
            return tryUnwrap(nbtList);
        }
        if (list instanceof byte[] bytes) {
            var values = new ArrayList<Object>(bytes.length);
            for (byte value : bytes) values.add(value);
            return values;
        }
        if (list instanceof int[] integers)
            return java.util.Arrays.stream(integers)
                    .mapToObj(value -> (Object) value)
                    .toList();
        if (list instanceof long[] longs)
            return java.util.Arrays.stream(longs)
                    .mapToObj(value -> (Object) value)
                    .toList();
        if (list == null) return Collections.emptyList();
        throw new IllegalArgumentException("Expected NBT text list: " + key);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> tryUnwrap(NbtList<?> list) {
        if (list.getType() != NbtType.COMPOUND) {
            return (List<Object>) list;
        }

        List<NbtMap> maps = (List<NbtMap>) list;
        List<Object> unwrapped = new ArrayList<>();
        for (NbtMap map : maps) {
            unwrapped.add(tryUnwrap(map));
        }
        return unwrapped;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void add(Object value) {
        if (value instanceof Boolean flag) value = (byte) (flag ? 1 : 0);
        if (value instanceof Number number
                && !(number instanceof Byte
                        || number instanceof Short
                        || number instanceof Integer
                        || number instanceof Long
                        || number instanceof Float
                        || number instanceof Double)) {
            var decimal = new java.math.BigDecimal(number.toString());
            try {
                long integer = decimal.longValueExact();
                if (integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE) value = (int) integer;
                else value = integer;
            } catch (ArithmeticException fractionalOrLarge) {
                value = decimal.doubleValue();
            }
        }
        add((NbtType) NbtType.byClass(value.getClass()), value);
    }

    public <T> void add(NbtType<T> type, T value) {
        types.add(type);
        values.add(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public NbtList<?> build() {
        NbtType<?> singleType = getSingleType();
        if (singleType != NbtType.COMPOUND) {
            return new NbtList(singleType, values);
        }
        return new NbtList(
                NbtType.COMPOUND,
                values.stream().map(HeterogeneousNbtList::wrapIfNeeded).toArray());
    }

    public static <T> Collector<T, ?, NbtList<?>> collector() {
        return new Collector<T, HeterogeneousNbtList, NbtList<?>>() {
            @Override
            public Supplier<HeterogeneousNbtList> supplier() {
                return HeterogeneousNbtList::new;
            }

            @Override
            public BiConsumer<HeterogeneousNbtList, T> accumulator() {
                return HeterogeneousNbtList::add;
            }

            @Override
            public BinaryOperator<HeterogeneousNbtList> combiner() {
                return (first, second) -> {
                    first.types.addAll(second.types);
                    first.values.addAll(second.values);
                    return first;
                };
            }

            @Override
            public Function<HeterogeneousNbtList, NbtList<?>> finisher() {
                return HeterogeneousNbtList::build;
            }

            @Override
            public Set<Characteristics> characteristics() {
                return Set.of();
            }
        };
    }

    private NbtType<?> getSingleType() {
        NbtType<?> singleType = NbtType.END;

        for (NbtType<?> type : types) {
            if (singleType == NbtType.END) {
                singleType = type;
            } else if (singleType != type) {
                return NbtType.COMPOUND;
            }
        }
        return singleType;
    }

    private static boolean isWrapper(NbtMap map) {
        return map.size() == 1 && map.containsKey("");
    }

    private static NbtMap wrapIfNeeded(Object object) {
        // type == compound
        return object instanceof NbtMap map && !isWrapper(map) ? map : wrapElement(object);
    }

    private static NbtMap wrapElement(Object object) {
        return NbtMap.fromMap(Map.of("", object));
    }

    private static Object tryUnwrap(NbtMap map) {
        if (map.size() == 1) {
            Object value = map.get("");
            if (value != null) {
                return value;
            }
        }
        return map;
    }
}
