package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import java.util.zip.Inflater;
import org.stackanvil.packetforge.commons.WireCodecs;

final class CompressionDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final StreamSettings settings;
    private Inflater inflater;

    CompressionDecoder(StreamSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) throws Exception {
        if (settings.threshold < 0) {
            output.add(input.retain());
            return;
        }
        int length = WireCodecs.VAR_INT.decode(input);
        if (length == 0) {
            output.add(input.readRetainedSlice(input.readableBytes()));
            return;
        }
        if (length < settings.threshold || length > StreamSettings.MAX_PACKET)
            throw new DecoderException("Invalid decompressed packet length: " + length);
        if (inflater == null) inflater = new Inflater();
        byte[] bytes = new byte[input.readableBytes()];
        input.readBytes(bytes);
        var decoded = context.alloc().buffer(length, length);
        try {
            inflater.setInput(bytes);
            var buffer = decoded.internalNioBuffer(0, length);
            int written = inflater.inflate(buffer);
            if (written != length) throw new DecoderException("Decompressed length differs from declared length");
            decoded.writerIndex(length);
            output.add(decoded);
        } catch (Exception failure) {
            decoded.release();
            throw failure;
        } finally {
            inflater.reset();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
    }
}
