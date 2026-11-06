package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import org.stackanvil.packetforge.commons.WireCodecs;

final class FrameEncoder extends MessageToByteEncoder<ByteBuf> {
    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf input, ByteBuf output) {
        int length = input.readableBytes();
        if (length > StreamSettings.MAX_FRAME) throw new EncoderException("Frame length exceeds 21 bits: " + length);
        WireCodecs.VAR_INT.encode(output, length);
        output.writeBytes(input, input.readerIndex(), length);
    }
}
