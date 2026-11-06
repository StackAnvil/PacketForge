package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

final class CipherDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final StreamSettings settings;

    CipherDecoder(StreamSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        var ciphers = settings.ciphers;
        if (ciphers == null) {
            output.add(input.retain());
            return;
        }
        byte[] bytes = new byte[input.readableBytes()];
        input.readBytes(bytes);
        byte[] decoded = ciphers.decrypt().update(bytes);
        if (decoded != null && decoded.length > 0)
            output.add(context.alloc().buffer(decoded.length).writeBytes(decoded));
    }
}
