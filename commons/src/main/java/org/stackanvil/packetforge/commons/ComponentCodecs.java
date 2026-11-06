package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Wire layouts shared by component patches and item stacks. Payload schemas are generated. */
public final class ComponentCodecs {
    private ComponentCodecs() {}

    public static PacketCodec<ComponentPatch> patch(Iterable<DataComponentType<?>> types, boolean delimited) {
        var table = new LinkedHashMap<Integer, DataComponentType<?>>();
        for (var type : types)
            if (table.putIfAbsent(type.id(), type) != null)
                throw new IllegalArgumentException("Duplicate component ID");
        int componentCount = table.size();
        for (int id = 0; id < componentCount; id++)
            if (!table.containsKey(id)) throw new IllegalArgumentException("Non-contiguous component schema table");
        return WireCodecs.of(
                input -> {
                    int added = count(input, componentCount), removed = count(input, componentCount);
                    if ((long) added + removed > componentCount)
                        throw new IllegalArgumentException("Too many component changes");
                    var values = new LinkedHashMap<Integer, DataComponent<?>>();
                    var removals = new LinkedHashSet<DataComponentType<?>>();
                    for (int i = 0; i < added; i++) {
                        int id = count(input, componentCount - 1);
                        var entry = decode(table.get(id), input, delimited);
                        if (values.putIfAbsent(id, entry) != null)
                            throw new IllegalArgumentException("Duplicate added component");
                    }
                    for (int i = 0; i < removed; i++) {
                        int id = count(input, componentCount - 1);
                        if (!removals.add(table.get(id)))
                            throw new IllegalArgumentException("Duplicate removed component");
                    }
                    return new ComponentPatch(values.values(), removals);
                },
                (output, value) -> {
                    if ((long) value.added().size() + value.removed().size() > componentCount)
                        throw new IllegalArgumentException("Too many component changes");
                    WireCodecs.VAR_INT.encode(output, value.added().size());
                    WireCodecs.VAR_INT.encode(output, value.removed().size());
                    for (var entry : value.added()) {
                        requireType(table, entry.type());
                        WireCodecs.VAR_INT.encode(output, entry.type().id());
                        entry.encode(output, delimited);
                    }
                    for (var type : value.removed()) {
                        requireType(table, type);
                        WireCodecs.VAR_INT.encode(output, type.id());
                    }
                });
    }

    private static <T> DataComponent<T> decode(DataComponentType<T> type, ByteBuf input, boolean delimited) {
        PacketCodec<T> codec = type.codec();
        if (delimited) codec = WireCodecs.lengthPrefixed(codec, Integer.MAX_VALUE);
        return type.withValue(codec.decode(input));
    }

    private static void requireType(java.util.Map<Integer, DataComponentType<?>> types, DataComponentType<?> type) {
        if (!type.equals(types.get(type.id())))
            throw new IllegalArgumentException("Unknown component key: " + type.key());
    }

    private static int count(ByteBuf input, int maximum) {
        int value = WireCodecs.VAR_INT.decode(input);
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid component count or ID: " + value);
        return value;
    }

    public static PacketCodec<ItemStack> validatedCount(PacketCodec<ItemStack> codec, int minimum, int maximum) {
        return WireCodecs.of(
                input -> {
                    ItemStack value = codec.decode(input);
                    if (value.count() != 0 && (value.count() < minimum || value.count() > maximum))
                        throw new IllegalArgumentException("Item count is outside its persistent range");
                    return value;
                },
                codec::encode);
    }

    public static PacketCodec<ItemStack> itemStack(PacketCodec<ComponentPatch> patch, boolean allowEmpty) {
        return WireCodecs.of(
                input -> {
                    int count = WireCodecs.VAR_INT.decode(input);
                    if (count <= 0) {
                        if (!allowEmpty) throw new IllegalArgumentException("Empty item stack");
                        return ItemStack.EMPTY;
                    }
                    return new ItemStack(count, WireCodecs.VAR_INT.decode(input), patch.decode(input));
                },
                (output, value) -> {
                    if (value.count() == 0 && !allowEmpty) throw new IllegalArgumentException("Empty item stack");
                    WireCodecs.VAR_INT.encode(output, value.count());
                    if (value.count() > 0) {
                        WireCodecs.VAR_INT.encode(output, value.item());
                        patch.encode(output, value.components());
                    }
                });
    }
}
