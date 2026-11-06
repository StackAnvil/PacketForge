package org.stackanvil.packetforge.commons;

import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;

/** A component's registry identity and the codec for its public value type. */
public record DataComponentType<T>(int id, Key key, PacketCodec<T> codec) implements Keyed {
    public DataComponentType {
        if (id < 0) throw new IllegalArgumentException("Negative component ID");
        Objects.requireNonNull(key);
        Objects.requireNonNull(codec);
    }

    public DataComponent<T> withValue(T value) {
        return new DataComponent<>(this, value);
    }

    @Override
    public String toString() {
        return key.asString() + "[" + id + "]";
    }
}
