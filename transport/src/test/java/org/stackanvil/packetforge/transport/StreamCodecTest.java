package org.stackanvil.packetforge.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.WireCodecs;

class StreamCodecTest {
    @Test
    void framesWaitForCompleteHeadersAndBodies() {
        var channel = new EmbeddedChannel(new FrameDecoder());
        try {
            assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0x80})));
            assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 7})));
            assertNull(channel.readInbound());
            var rest = Unpooled.buffer().writeZero(127).writeByte(1).writeByte(42);
            assertTrue(channel.writeInbound(rest));
            ByteBuf first = channel.readInbound();
            ByteBuf second = channel.readInbound();
            try {
                assertEquals(128, first.readableBytes());
                assertEquals(7, first.readUnsignedByte());
                assertEquals(42, second.readUnsignedByte());
                assertNull(channel.readInbound());
            } finally {
                first.release();
                second.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void frameDecoderRejectsZeroAndWideHeaders() {
        for (byte[] bytes : new byte[][] {{0}, {(byte) 0x80, 0}, {(byte) 0x80, (byte) 0x80, (byte) 0x80}}) {
            var channel = new EmbeddedChannel(new FrameDecoder());
            try {
                assertThrows(DecoderException.class, () -> channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
            } finally {
                channel.close();
                channel.releaseInbound();
            }
        }
    }

    @Test
    void framesRetainTheFullTwentyOneBitRange() {
        var channel = new EmbeddedChannel(new FrameEncoder(), new FrameDecoder());
        try {
            assertTrue(channel.writeOutbound(
                    Unpooled.buffer(StreamSettings.MAX_FRAME).writeZero(StreamSettings.MAX_FRAME)));
            ByteBuf encoded = channel.readOutbound();
            assertEquals(StreamSettings.MAX_FRAME + 3, encoded.readableBytes());
            assertTrue(channel.writeInbound(encoded));
            ByteBuf decoded = channel.readInbound();
            assertEquals(StreamSettings.MAX_FRAME, decoded.readableBytes());
            decoded.release();
            assertThrows(
                    EncoderException.class,
                    () -> channel.writeOutbound(
                            Unpooled.buffer(StreamSettings.MAX_FRAME + 1).writeZero(StreamSettings.MAX_FRAME + 1)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void compressionResetsForEachPacketAndHonorsThreshold() {
        for (int threshold : new int[] {-1, 0, 32, 256}) {
            var settings = new StreamSettings();
            settings.threshold = threshold;
            var channel = new EmbeddedChannel(new CompressionDecoder(settings), new CompressionEncoder(settings));
            try {
                for (int length : new int[] {1, 31, 32, 255, 256, 8193, 1024 * 1024, 7}) {
                    byte[] bytes = new byte[length];
                    Arrays.fill(bytes, (byte) 71);
                    channel.writeOutbound(Unpooled.wrappedBuffer(bytes));
                    ByteBuf encoded = channel.readOutbound();
                    if (threshold >= 0) {
                        int index = encoded.readerIndex();
                        assertEquals(length < threshold ? 0 : length, WireCodecs.VAR_INT.decode(encoded));
                        encoded.readerIndex(index);
                    }
                    assertTrue(channel.writeInbound(encoded));
                    ByteBuf decoded = channel.readInbound();
                    try {
                        assertArrayEquals(bytes, bytes(decoded));
                    } finally {
                        decoded.release();
                    }
                }
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void compressionRejectsInvalidDeclaredLengthsAndTruncatedData() {
        for (int length : new int[] {-1, 1, StreamSettings.MAX_PACKET + 1, 64}) {
            var settings = new StreamSettings();
            settings.threshold = 32;
            var channel = new EmbeddedChannel(new CompressionDecoder(settings));
            ByteBuf input = Unpooled.buffer();
            WireCodecs.VAR_INT.encode(input, length);
            input.writeByte(1);
            try {
                assertThrows(DecoderException.class, () -> channel.writeInbound(input));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void uncompressedBranchDoesNotRequirePayloadToBeBelowThreshold() {
        var settings = new StreamSettings();
        settings.threshold = 1;
        var channel = new EmbeddedChannel(new CompressionDecoder(settings));
        try {
            channel.writeInbound(Unpooled.buffer().writeByte(0).writeZero(128));
            ByteBuf result = channel.readInbound();
            assertEquals(128, result.readableBytes());
            result.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void compressionChecksTheEightMiBLimitBeforeCompressing() {
        var settings = new StreamSettings();
        settings.threshold = 0;
        var channel = new EmbeddedChannel(new CompressionEncoder(settings));
        try {
            assertThrows(
                    EncoderException.class,
                    () -> channel.writeOutbound(
                            Unpooled.buffer(StreamSettings.MAX_PACKET + 1).writeZero(StreamSettings.MAX_PACKET + 1)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void ciphersKeepStateAcrossDifferentChunkBoundaries() throws Exception {
        var key = new SecretKeySpec(new byte[16], "AES");
        var settings = new StreamSettings();
        settings.ciphers = settings.prepare(key);
        var channel = new EmbeddedChannel(new CipherDecoder(settings), new CipherEncoder(settings));
        byte[] plain = new byte[1027];
        new java.util.Random(481).nextBytes(plain);
        var encrypted = Unpooled.buffer();
        var decoded = Unpooled.buffer();
        try {
            for (int start = 0; start < plain.length; start += 17) {
                channel.writeOutbound(
                        Unpooled.wrappedBuffer(Arrays.copyOfRange(plain, start, Math.min(start + 17, plain.length))));
                ByteBuf next = channel.readOutbound();
                encrypted.writeBytes(next);
                next.release();
            }
            Cipher expected = Cipher.getInstance("AES/CFB8/NoPadding");
            expected.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
            assertArrayEquals(expected.doFinal(plain), bytes(encrypted));
            while (encrypted.isReadable()) {
                channel.writeInbound(encrypted.readRetainedSlice(Math.min(31, encrypted.readableBytes())));
                ByteBuf next = channel.readInbound();
                decoded.writeBytes(next);
                next.release();
            }
            assertArrayEquals(plain, bytes(decoded));
            assertThrows(IllegalStateException.class, () -> settings.prepare(key));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StreamSettings().prepare(new SecretKeySpec(new byte[24], "AES")));
        } finally {
            encrypted.release();
            decoded.release();
            channel.finishAndReleaseAll();
        }
    }

    static byte[] bytes(ByteBuf input) {
        byte[] result = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), result);
        return result;
    }
}
