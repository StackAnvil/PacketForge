package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.util.Objects;

/** A component key paired with a value of the key's declared type. */
public record DataComponent<T>(DataComponentType<T> type, T value) {
    public DataComponent {
        Objects.requireNonNull(type);
        Objects.requireNonNull(value);
    }

    void encode(ByteBuf output, boolean delimited) {
        PacketCodec<T> codec = type.codec();
        if (delimited) codec = WireCodecs.lengthPrefixed(codec, Integer.MAX_VALUE);
        codec.encode(output, value);
    }
}
