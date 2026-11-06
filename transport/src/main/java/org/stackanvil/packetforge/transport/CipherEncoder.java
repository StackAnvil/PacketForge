package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

final class CipherEncoder extends MessageToByteEncoder<ByteBuf> {
    private final StreamSettings settings;

    CipherEncoder(StreamSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf input, ByteBuf output) {
        var ciphers = settings.ciphers;
        if (ciphers == null) {
            output.writeBytes(input);
            return;
        }
        byte[] bytes = new byte[input.readableBytes()];
        input.readBytes(bytes);
        byte[] encoded = ciphers.encrypt().update(bytes);
        if (encoded != null) output.writeBytes(encoded);
    }
}
