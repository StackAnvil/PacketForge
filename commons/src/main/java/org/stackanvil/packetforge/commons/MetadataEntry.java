package org.stackanvil.packetforge.commons;

import java.util.Objects;

/** One indexed entity-data value, including its serializer ID. */
public record MetadataEntry<T extends TypedPayload<Integer>>(int index, T value) {
    public MetadataEntry {
        if (index < 0 || index >= 255) throw new IllegalArgumentException("Invalid metadata index: " + index);
        Objects.requireNonNull(value);
    }
}
