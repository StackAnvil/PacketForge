package org.stackanvil.packetforge.commons;

import java.util.Map;
import java.util.Set;

/** Added component wire values and removed component registry IDs. */
public record ComponentPatch(Map<Integer, Object> added, Set<Integer> removed) {
    public static final ComponentPatch EMPTY = new ComponentPatch(Map.of(), Set.of());
    public ComponentPatch {
        added = Map.copyOf(added); removed = Set.copyOf(removed);
        if (added.keySet().stream().anyMatch(removed::contains)) throw new IllegalArgumentException("Component is both added and removed");
        if (added.keySet().stream().anyMatch(id -> id < 0) || removed.stream().anyMatch(id -> id < 0)) throw new IllegalArgumentException("Negative component ID");
    }
}
