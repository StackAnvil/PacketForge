package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

final class FrameDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        int start = input.readerIndex(), length = 0;
        for (int i = 0; i < 3; i++) {
            if (input.readableBytes() <= i) return;
            int value = input.getUnsignedByte(start + i);
            length |= (value & 127) << (7 * i);
            if ((value & 128) == 0) {
                if (length == 0) throw new CorruptedFrameException("Frame length cannot be zero");
                if (input.readableBytes() - i - 1 < length) return;
                input.skipBytes(i + 1);
                output.add(input.readRetainedSlice(length));
                return;
            }
        }
        throw new CorruptedFrameException("Frame length exceeds 21 bits");
    }
}
