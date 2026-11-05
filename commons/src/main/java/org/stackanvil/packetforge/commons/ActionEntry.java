package org.stackanvil.packetforge.commons;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An entry identifier and the payload values selected by its packet's action mask. */
public record ActionEntry<K extends Enum<K>>(UUID id, Map<K, Object> values) {
    public ActionEntry { Objects.requireNonNull(id); values = Map.copyOf(values); }
}
