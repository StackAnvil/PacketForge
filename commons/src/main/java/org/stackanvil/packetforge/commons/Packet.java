package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;

/** A packet body. Framing, compression, encryption, and sessions belong to the caller. */
public interface Packet {
    void serialize(ByteBuf output);
}
