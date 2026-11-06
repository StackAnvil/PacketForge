package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.zip.Deflater;
import org.stackanvil.packetforge.commons.WireCodecs;

final class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {
    private final StreamSettings settings;
    private final byte[] scratch = new byte[8192];
    private Deflater deflater;

    CompressionEncoder(StreamSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf input, ByteBuf output) {
        int length = input.readableBytes();
        if (length > StreamSettings.MAX_PACKET) throw new EncoderException("Packet exceeds 8 MiB: " + length);
        if (settings.threshold < 0) {
            output.writeBytes(input);
            return;
        }
        if (length < settings.threshold) {
            WireCodecs.VAR_INT.encode(output, 0);
            output.writeBytes(input);
            return;
        }
        if (deflater == null) deflater = new Deflater();
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        WireCodecs.VAR_INT.encode(output, length);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            while (!deflater.finished()) output.writeBytes(scratch, 0, deflater.deflate(scratch));
        } finally {
            deflater.reset();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        if (deflater != null) {
            deflater.end();
            deflater = null;
        }
    }
}
