package org.stackanvil.packetforge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.packet.common.*;
import org.stackanvil.packetforge.protocol.packet.configuration.ClientboundFinishConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.game.*;
import org.stackanvil.packetforge.protocol.packet.login.ServerboundHelloPacket;
import org.stackanvil.packetforge.protocol.packet.ping.ServerboundPingRequestPacket;
import static org.junit.jupiter.api.Assertions.*;

/** The generator stays static. Only this test loads vanilla in an isolated class loader. */
class VanillaCompatibilityTest {
    @Test void generatedBodiesAgreeWithVanillaDecodersAndEncoders() throws Exception {
        Path directory = Path.of(System.getProperty("packetforge.cache"), System.getProperty("packetforge.version"));
        List<URL> urls = new ArrayList<>(); urls.add(directory.resolve("minecraft.jar").toUri().toURL());
        try (Stream<Path> libraries = Files.walk(directory.resolve("libraries"))) {
            for (Path library : libraries.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) urls.add(library.toUri().toURL());
        }
        List<Packet> samples = new ArrayList<>(List.of(
            new ClientboundKeepAlivePacket(Long.MIN_VALUE),
            new ClientboundTransferPacket("localhost", 25565),
            new ClientboundTransferPacket("münchen.example", 65535),
            new ClientboundSetHealthPacket(17.5f, 20, 3.25f),
            new ClientboundAnimatePacket(345, 255),
            new ClientboundBlockChangedAckPacket(-1),
            new ClientboundSetChunkCacheCenterPacket(-1234, 5678),
            new ClientboundOpenSignEditorPacket(new BlockPosition(-33554432, -2048, 33554431), org.stackanvil.packetforge.protocol.type.world.level.block.entity.SignTextSlot.FRONT),
            new ServerboundHelloPacket("Player", UUID.fromString("12345678-9abc-def0-1234-56789abcdef0")),
            new ClientboundResetScorePacket("owner", null),
            new ClientboundResetScorePacket("owner", "objective"),
            new ClientboundCustomChatCompletionsPacket(ClientboundCustomChatCompletionsPacket_Action.REMOVE, List.of("hello", "café")),
            new ClientboundCooldownPacket("minecraft:stone", 128),
            new ServerboundClientCommandPacket(ServerboundClientCommandPacket_Action.REQUEST_STATS),
            new ServerboundUseItemPacket(org.stackanvil.packetforge.protocol.type.world.InteractionHand.OFF_HAND, 12345, 180f, -90f),
            new ServerboundPingRequestPacket(Long.MAX_VALUE),
            new ClientboundFinishConfigurationPacket()
        ));
        var models = GeneratedRoundTripTest.models();
        for (var entry : GeneratedProtocol.registry().entries().values()) for (int variant = 0; variant < 3; variant++) {
            samples.add((Packet) GeneratedRoundTripTest.sample(entry.type(), "", variant, models));
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> byteBuf = loader.loadClass("io.netty.buffer.ByteBuf");
            Class<?> unpooled = loader.loadClass("io.netty.buffer.Unpooled");
            Class<?> friendly = loader.loadClass("net.minecraft.network.RegistryFriendlyByteBuf");
            Class<?> registryAccess = loader.loadClass("net.minecraft.core.RegistryAccess");
            loader.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
            loader.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            Object builtIns = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null);
            Object registries = registryAccess.getMethod("fromRegistryOfRegistries", loader.loadClass("net.minecraft.core.Registry")).invoke(null, builtIns);
            Class<?> streamCodec = loader.loadClass("net.minecraft.network.codec.StreamCodec");
            Method decode = streamCodec.getMethod("decode", Object.class);
            Method encode = streamCodec.getMethod("encode", Object.class, Object.class);
            for (Packet packet : samples) {
                String original = "net.minecraft.network.protocol." + packet.getClass().getName().substring("org.stackanvil.packetforge.protocol.packet.".length());
                Class<?> vanilla = loader.loadClass(original);
                Object codec = loader.loadClass("net.minecraft.network.protocol.BundleDelimiterPacket").isAssignableFrom(vanilla)
                    ? streamCodec.getMethod("unit", Object.class).invoke(null, vanilla.getConstructor().newInstance())
                    : vanilla.getField("STREAM_CODEC").get(null);
                byte[] expected = body(packet);
                Object inputRaw = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) expected);
                Object outputRaw = unpooled.getMethod("buffer").invoke(null);
                try {
                    Object input = friendly.getConstructor(byteBuf, registryAccess).newInstance(inputRaw, registries);
                    Object output = friendly.getConstructor(byteBuf, registryAccess).newInstance(outputRaw, registries);
                    Object vanillaPacket = decode.invoke(codec, input);
                    assertEquals(0, byteBuf.getMethod("readableBytes").invoke(inputRaw), original);
                    encode.invoke(codec, output, vanillaPacket);
                    int length = (int) byteBuf.getMethod("readableBytes").invoke(outputRaw);
                    byte[] actual = new byte[length]; byteBuf.getMethod("readBytes", byte[].class).invoke(outputRaw, (Object) actual);
                    assertArrayEquals(expected, actual, original);
                } catch (Exception e) { throw new AssertionError(original, e); }
                finally {
                    byteBuf.getMethod("release").invoke(inputRaw); byteBuf.getMethod("release").invoke(outputRaw);
                }
            }
        }
    }
    @Test void registryKeepsTheCompleteCatalogueAndBundleOffset() {
        assertEquals(777, GeneratedProtocol.PROTOCOL_VERSION);
        assertEquals(260, GeneratedProtocol.DESCRIPTORS.size());
        var play = GeneratedProtocol.DESCRIPTORS.stream().filter(d -> d.key().state() == PacketRegistry.State.PLAY && d.key().flow() == PacketRegistry.Flow.CLIENTBOUND).toList();
        assertEquals("minecraft:bundle_delimiter", play.getFirst().name());
        assertEquals(0, play.getFirst().key().id());
        assertEquals(1, play.get(1).key().id());
        for (var descriptor : GeneratedProtocol.DESCRIPTORS) {
            assertEquals(descriptor.supported(), GeneratedProtocol.registry().entries().containsKey(descriptor.key()));
            if (!descriptor.supported()) assertFalse(descriptor.reason().isBlank());
        }
    }
    private static byte[] body(Packet packet) {
        ByteBuf buffer = Unpooled.buffer();
        try { packet.serialize(buffer); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes; }
        finally { buffer.release(); }
    }
}
