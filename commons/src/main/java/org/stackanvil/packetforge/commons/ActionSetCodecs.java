package org.stackanvil.packetforge.commons;

import java.util.*;

/** Codecs whose entry payloads depend on an enclosing enum action mask. */
public final class ActionSetCodecs {
    private ActionSetCodecs() {}
    public static <K extends Enum<K>> PacketCodec<Set<K>> mask(K[] constants) {
        K[] values = constants.clone();
        for (int i = 0; i < values.length; i++) if (values[i].ordinal() != i) throw new IllegalArgumentException("Action constants are not in ordinal order");
        return WireCodecs.mapped(WireCodecs.fixedBitSet(values.length), bits -> {
            Set<K> selected = new LinkedHashSet<>();
            for (int i = 0; i < values.length; i++) if (bits.get(i)) selected.add(values[i]);
            return Set.copyOf(selected);
        }, selected -> {
            BitSet bits = new BitSet(values.length);
            for (K value : selected) {
                int id = value.ordinal();
                if (id >= values.length || values[id] != value) throw new IllegalArgumentException("Unknown action constant");
                bits.set(id);
            }
            return bits;
        });
    }
    public static <K extends Enum<K>> PacketCodec<ActionEntry<K>> entry(Set<K> selected, Map<K, PacketCodec<?>> schemas) {
        List<K> actions = selected.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        Map<K, PacketCodec<?>> codecs = Map.copyOf(schemas);
        if (!codecs.keySet().containsAll(actions)) throw new IllegalArgumentException("Missing action payload schema");
        return WireCodecs.of(input -> {
            UUID id = WireCodecs.UUID_CODEC.decode(input); Map<K, Object> values = new LinkedHashMap<>();
            for (K action : actions) values.put(action, codecs.get(action).decode(input));
            return new ActionEntry<>(id, values);
        }, (output, entry) -> {
            if (!entry.values().keySet().containsAll(actions)) throw new IllegalArgumentException("Entry is missing a selected action payload");
            WireCodecs.UUID_CODEC.encode(output, entry.id());
            for (K action : actions) write(codecs.get(action), output, entry.values().get(action));
        });
    }
    @SuppressWarnings("unchecked") private static void write(PacketCodec<?> codec, io.netty.buffer.ByteBuf output, Object value) { ((PacketCodec<Object>) codec).encode(output, value); }
}
