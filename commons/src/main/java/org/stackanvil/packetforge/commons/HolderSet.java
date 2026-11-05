package org.stackanvil.packetforge.commons;

import java.util.List;
import java.util.Objects;

/** A registry tag or an explicit list of registry IDs. */
public sealed interface HolderSet permits HolderSet.Tag, HolderSet.Direct {
    record Tag(String name) implements HolderSet {
        public Tag {
            Objects.requireNonNull(name);
        }
    }

    record Direct(List<Integer> ids) implements HolderSet {
        public Direct {
            ids = List.copyOf(ids);
        }
    }
}
