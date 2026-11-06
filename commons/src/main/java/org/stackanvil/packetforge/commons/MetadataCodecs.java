package org.stackanvil.packetforge.commons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Entity metadata ends with an unsigned-byte sentinel rather than a list count. */
public final class MetadataCodecs {
    private MetadataCodecs() {}

    public static <T extends TypedPayload<Integer>> PacketCodec<List<MetadataEntry<T>>> entries(
            PacketCodec<T> serializers) {
        return WireCodecs.of(
                input -> {
                    List<MetadataEntry<T>> values = new ArrayList<>();
                    var indices = new HashSet<Integer>();
                    for (int index; (index = input.readUnsignedByte()) != 255; ) {
                        if (!indices.add(index))
                            throw new IllegalArgumentException("Repeated metadata index: " + index);
                        values.add(new MetadataEntry<>(index, serializers.decode(input)));
                    }
                    return List.copyOf(values);
                },
                (output, values) -> {
                    var indices = new HashSet<Integer>();
                    for (var entry : values) {
                        if (!indices.add(entry.index()))
                            throw new IllegalArgumentException("Repeated metadata index: " + entry.index());
                        output.writeByte(entry.index());
                        serializers.encode(output, entry.value());
                    }
                    output.writeByte(255);
                });
    }
}
