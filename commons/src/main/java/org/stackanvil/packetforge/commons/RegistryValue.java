package org.stackanvil.packetforge.commons;

import java.util.Objects;

public sealed interface RegistryValue<T> {
    record Reference<T>(int id) implements RegistryValue<T> {
        public Reference { if (id < 0 || id == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid registry ID"); }
    }
    record Direct<T>(T value) implements RegistryValue<T> { public Direct { Objects.requireNonNull(value); } }
}
