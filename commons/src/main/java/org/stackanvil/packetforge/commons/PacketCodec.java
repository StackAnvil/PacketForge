package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;

public interface PacketCodec<T> {
    T decode(ByteBuf input);

    void encode(ByteBuf output, T value);
}
