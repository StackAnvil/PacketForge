package org.stackanvil.packetforge.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.WireCodecs;

/** Only compatibility tests load Minecraft. Production transport code uses the generated registry. */
class VanillaStreamCompatibilityTest {
    @Test
    void framingAgreesWithVanillaAtBoundaries() throws Exception {
        try (var vanilla = new Vanilla()) {
            for (int length : new int[] {1, 127, 128, 16383, 16384, StreamSettings.MAX_FRAME}) {
                byte[] payload = new byte[length];
                Arrays.fill(payload, (byte) 42);
                try (var nativeChannel = vanilla.channel(vanilla.handler("Varint21LengthFieldPrepender"))) {
                    var ours = new EmbeddedChannel(new FrameEncoder());
                    try {
                        ours.writeOutbound(Unpooled.wrappedBuffer(payload));
                        ByteBuf encoded = ours.readOutbound();
                        byte[] frame;
                        try {
                            frame = StreamCodecTest.bytes(encoded);
                        } finally {
                            encoded.release();
                        }
                        nativeChannel.write(false, payload);
                        assertArrayEquals(frame, nativeChannel.read(false));
                        Object nativeDecoder = vanilla.type("Varint21FrameDecoder")
                                .getConstructor(vanilla.type("BandwidthDebugMonitor"))
                                .newInstance(new Object[] {null});
                        try (var nativeFrames = vanilla.channel(nativeDecoder)) {
                            var frames = new EmbeddedChannel(new FrameDecoder());
                            try {
                                int split = Math.min(2, frame.length - 1);
                                nativeFrames.write(true, Arrays.copyOf(frame, split));
                                frames.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(frame, split)));
                                assertNull(nativeFrames.read(true));
                                assertNull(frames.readInbound());
                                nativeFrames.write(true, Arrays.copyOfRange(frame, split, frame.length));
                                frames.writeInbound(
                                        Unpooled.wrappedBuffer(Arrays.copyOfRange(frame, split, frame.length)));
                                ByteBuf decoded = frames.readInbound();
                                try {
                                    assertArrayEquals(nativeFrames.read(true), StreamCodecTest.bytes(decoded));
                                } finally {
                                    decoded.release();
                                }
                            } finally {
                                frames.finishAndReleaseAll();
                            }
                        }
                    } finally {
                        ours.finishAndReleaseAll();
                    }
                }
            }
        }
    }

    @Test
    void compressionBytesAndDecodedPayloadsAgreeWithVanilla() throws Exception {
        try (var vanilla = new Vanilla()) {
            for (int threshold : new int[] {0, 32, 256}) {
                Object nativeEncoder = vanilla.type("CompressionEncoder")
                        .getConstructor(int.class)
                        .newInstance(threshold);
                Object nativeDecoder = vanilla.type("CompressionDecoder")
                        .getConstructor(int.class, boolean.class)
                        .newInstance(threshold, true);
                try (var nativeChannel = vanilla.channel(nativeDecoder, nativeEncoder)) {
                    var settings = new StreamSettings();
                    settings.threshold = threshold;
                    var ours = new EmbeddedChannel(new CompressionDecoder(settings), new CompressionEncoder(settings));
                    try {
                        for (int length : new int[] {1, 31, 32, 255, 256, 8193, StreamSettings.MAX_PACKET}) {
                            byte[] payload = new byte[length];
                            Arrays.fill(payload, (byte) 29);
                            nativeChannel.write(false, payload);
                            ours.writeOutbound(Unpooled.wrappedBuffer(payload));
                            byte[] nativeBytes = nativeChannel.read(false);
                            ByteBuf generated = ours.readOutbound();
                            try {
                                assertArrayEquals(nativeBytes, StreamCodecTest.bytes(generated));
                            } finally {
                                generated.release();
                            }
                            nativeChannel.write(true, nativeBytes);
                            ours.writeInbound(Unpooled.wrappedBuffer(nativeBytes));
                            ByteBuf decoded = ours.readInbound();
                            try {
                                assertArrayEquals(nativeChannel.read(true), StreamCodecTest.bytes(decoded));
                            } finally {
                                decoded.release();
                            }
                        }
                    } finally {
                        ours.finishAndReleaseAll();
                    }
                } finally {
                    vanilla.end(nativeEncoder, "deflater");
                    vanilla.end(nativeDecoder, "inflater");
                }
            }
        }
    }

    @Test
    void declaredLengthAndZlibValidationMatchVanilla() throws Exception {
        try (var vanilla = new Vanilla()) {
            // Vanilla accepts a missing zlib checksum or trailing bytes when inflate produces the declared count.
            var encoded = new EmbeddedChannel(new CompressionEncoder(compressing(32)));
            byte[] complete;
            try {
                encoded.writeOutbound(Unpooled.wrappedBuffer(new byte[128]));
                ByteBuf frame = encoded.readOutbound();
                try {
                    complete = StreamCodecTest.bytes(frame);
                } finally {
                    frame.release();
                }
            } finally {
                encoded.finishAndReleaseAll();
            }
            List<byte[]> cases = new ArrayList<>();
            cases.add(complete);
            cases.add(Arrays.copyOf(complete, complete.length - 4));
            cases.add(Arrays.copyOf(complete, complete.length + 3));
            cases.add(Arrays.copyOf(complete, 4));
            for (int length : new int[] {-1, 1, StreamSettings.MAX_PACKET + 1}) {
                ByteBuf packet = Unpooled.buffer();
                try {
                    WireCodecs.VAR_INT.encode(packet, length);
                    packet.writeByte(0);
                    cases.add(StreamCodecTest.bytes(packet));
                } finally {
                    packet.release();
                }
            }
            for (byte[] bytes : cases) {
                Object nativeDecoder = vanilla.type("CompressionDecoder")
                        .getConstructor(int.class, boolean.class)
                        .newInstance(32, true);
                try (var nativeChannel = vanilla.channel(nativeDecoder)) {
                    var ours = new EmbeddedChannel(new CompressionDecoder(compressing(32)));
                    try {
                        try {
                            nativeChannel.write(true, bytes);
                        } catch (InvocationTargetException invalid) {
                            assertTrue(invalid.getCause().getClass().getName().contains("DecoderException"));
                            assertThrows(
                                    io.netty.handler.codec.DecoderException.class,
                                    () -> ours.writeInbound(Unpooled.wrappedBuffer(bytes)));
                            continue;
                        }
                        ours.writeInbound(Unpooled.wrappedBuffer(bytes));
                        ByteBuf decoded = ours.readInbound();
                        try {
                            assertArrayEquals(nativeChannel.read(true), StreamCodecTest.bytes(decoded));
                        } finally {
                            decoded.release();
                        }
                    } finally {
                        ours.finishAndReleaseAll();
                    }
                } finally {
                    vanilla.end(nativeDecoder, "inflater");
                }
            }
        }
    }

    @Test
    void cipherStreamsAgreeWithVanillaAcrossChunks() throws Exception {
        var key = new SecretKeySpec(new byte[16], "AES");
        var oursSettings = new StreamSettings();
        oursSettings.ciphers = oursSettings.prepare(key);
        var nativeSettings = new StreamSettings();
        var nativeCiphers = nativeSettings.prepare(key);
        try (var vanilla = new Vanilla();
                var nativeChannel = vanilla.channel(
                        vanilla.type("CipherDecoder")
                                .getConstructor(Cipher.class)
                                .newInstance(nativeCiphers.decrypt()),
                        vanilla.type("CipherEncoder")
                                .getConstructor(Cipher.class)
                                .newInstance(nativeCiphers.encrypt()))) {
            var ours = new EmbeddedChannel(new CipherDecoder(oursSettings), new CipherEncoder(oursSettings));
            try {
                for (int length : new int[] {1, 17, 31, 1027, 3}) {
                    byte[] bytes = new byte[length];
                    new java.util.Random(length).nextBytes(bytes);
                    nativeChannel.write(false, bytes);
                    ours.writeOutbound(Unpooled.wrappedBuffer(bytes));
                    ByteBuf encrypted = ours.readOutbound();
                    byte[] encryptedBytes;
                    try {
                        encryptedBytes = StreamCodecTest.bytes(encrypted);
                    } finally {
                        encrypted.release();
                    }
                    assertArrayEquals(nativeChannel.read(false), encryptedBytes);
                    nativeChannel.write(true, encryptedBytes);
                    ours.writeInbound(Unpooled.wrappedBuffer(encryptedBytes));
                    ByteBuf decoded = ours.readInbound();
                    try {
                        assertArrayEquals(nativeChannel.read(true), StreamCodecTest.bytes(decoded));
                    } finally {
                        decoded.release();
                    }
                }
            } finally {
                ours.finishAndReleaseAll();
            }
        }
    }

    private static StreamSettings compressing(int threshold) {
        var settings = new StreamSettings();
        settings.threshold = threshold;
        return settings;
    }

    private static final class Vanilla implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> channelType;
        final Class<?> bufferType;
        final Class<?> unpooledType;

        Vanilla() throws Exception {
            Path cache = Path.of(System.getProperty("packetforge.cache"), System.getProperty("packetforge.version"));
            var urls = new ArrayList<URL>();
            urls.add(cache.resolve("minecraft.jar").toUri().toURL());
            try (var libraries = Files.walk(cache.resolve("libraries"))) {
                for (var jar : libraries
                        .filter(path -> path.toString().endsWith(".jar"))
                        .sorted()
                        .toList()) urls.add(jar.toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            channelType = loader.loadClass("io.netty.channel.embedded.EmbeddedChannel");
            bufferType = loader.loadClass("io.netty.buffer.ByteBuf");
            unpooledType = loader.loadClass("io.netty.buffer.Unpooled");
        }

        Class<?> type(String name) throws Exception {
            return loader.loadClass("net.minecraft.network." + name);
        }

        Object handler(String name) throws Exception {
            return type(name).getConstructor().newInstance();
        }

        NativeChannel channel(Object... handlers) throws Exception {
            Class<?> handlerType = loader.loadClass("io.netty.channel.ChannelHandler");
            Object array = Array.newInstance(handlerType, handlers.length);
            for (int i = 0; i < handlers.length; i++) Array.set(array, i, handlers[i]);
            return new NativeChannel(
                    channelType.getConstructor(array.getClass()).newInstance(array));
        }

        void end(Object codec, String name) throws Exception {
            var field = codec.getClass().getDeclaredField(name);
            field.setAccessible(true);
            switch (field.get(codec)) {
                case Deflater deflater -> deflater.end();
                case Inflater inflater -> inflater.end();
                default -> fail("Unknown zlib resource");
            }
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }

        final class NativeChannel implements AutoCloseable {
            final Object channel;

            NativeChannel(Object channel) {
                this.channel = channel;
            }

            void write(boolean inbound, byte[] bytes) throws Exception {
                Object input =
                        unpooledType.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
                channelType
                        .getMethod(inbound ? "writeInbound" : "writeOutbound", Object[].class)
                        .invoke(channel, (Object) new Object[] {input});
            }

            byte[] read(boolean inbound) throws Exception {
                Object output = channelType
                        .getMethod(inbound ? "readInbound" : "readOutbound")
                        .invoke(channel);
                if (output == null) return null;
                try {
                    int length = (int) bufferType.getMethod("readableBytes").invoke(output);
                    byte[] bytes = new byte[length];
                    bufferType.getMethod("readBytes", byte[].class).invoke(output, (Object) bytes);
                    return bytes;
                } finally {
                    bufferType.getMethod("release").invoke(output);
                }
            }

            @Override
            public void close() throws Exception {
                try {
                    channelType.getMethod("finishAndReleaseAll").invoke(channel);
                } catch (InvocationTargetException invalid) {
                    // ByteToMessageDecoder retries a retained malformed frame while closing.
                    if (!invalid.getCause().getClass().getName().contains("DecoderException")) throw invalid;
                }
            }
        }
    }
}
