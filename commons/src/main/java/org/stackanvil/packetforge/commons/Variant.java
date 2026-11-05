package org.stackanvil.packetforge.commons;

import java.util.Objects;

/** A discriminator paired with the payload defined by its generated codec table. */
public record Variant<K>(K type, Object value) {
    public Variant { Objects.requireNonNull(type); }
}
