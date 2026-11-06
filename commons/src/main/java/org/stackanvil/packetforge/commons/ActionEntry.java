package org.stackanvil.packetforge.commons;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An entry identifier and the payload values selected by its packet's action mask. */
public record ActionEntry<K extends Enum<K>, P extends TypedPayload<K>>(UUID id, Map<K, P> values) {
    public ActionEntry {
        Objects.requireNonNull(id);
        values = Map.copyOf(values);
        for (var entry : values.entrySet())
            if (entry.getKey() != entry.getValue().type())
                throw new IllegalArgumentException("Action key does not match its payload");
    }
}
