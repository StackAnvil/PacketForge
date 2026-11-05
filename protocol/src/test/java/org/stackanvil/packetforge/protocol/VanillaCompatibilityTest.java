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
        for (var entry : GeneratedProtocol.registry().entries().values()) {
            for (int variant = 0; variant < 3; variant++) samples.add((Packet) GeneratedRoundTripTest.sample(entry.type(), "", variant, models));
            var model = models.get(entry.type().getName());
            if (model.has("program") && !model.getAsJsonObject("program").getAsJsonArray("operations").toString().matches(".*(REGISTRY|NBT|JSON|holder).*")) {
                byte[] baseline = body((Packet) GeneratedRoundTripTest.sample(entry.type(), "", 0, models));
                for (int index = 0; index < Math.min(baseline.length, 128); index++) for (int bit = 0; bit < 8; bit++) {
                    byte[] mutation = baseline.clone(); mutation[index] ^= (byte) (1 << bit);
                    ByteBuf input = Unpooled.wrappedBuffer(mutation);
                    try { Packet packet = entry.codec().decode(input); if (!input.isReadable()) samples.add(packet); }
                    catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { }
                    finally { input.release(); }
                }
            }
        }
        System.out.println("Comparing " + samples.size() + " generated packet bodies with vanilla codecs");
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> byteBuf = loader.loadClass("io.netty.buffer.ByteBuf");
            Class<?> unpooled = loader.loadClass("io.netty.buffer.Unpooled");
            Class<?> friendly = loader.loadClass("net.minecraft.network.RegistryFriendlyByteBuf");
            Class<?> registryAccess = loader.loadClass("net.minecraft.core.RegistryAccess");
            loader.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
            loader.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            Object builtIns = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null);
            Object registries = worldRegistries(loader, builtIns, models);
            Class<?> streamCodec = loader.loadClass("net.minecraft.network.codec.StreamCodec");
            Method decode = streamCodec.getMethod("decode", Object.class);
            Method encode = streamCodec.getMethod("encode", Object.class, Object.class);
            for (Packet packet : samples) {
                String original = models.get(packet.getClass().getName()).get("originalName").getAsString();
                Class<?> vanilla = loadOriginal(loader, original);
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
                    if (!java.util.Arrays.equals(expected, actual)) assertSameWireValues(packet.getClass(), expected, actual, original);
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
    private static void assertSameWireValues(Class<?> packet, byte[] expected, byte[] actual, String name) throws Exception {
        @SuppressWarnings("unchecked") PacketCodec<Object> codec = (PacketCodec<Object>) packet.getField("CODEC").get(null);
        ByteBuf left = Unpooled.wrappedBuffer(expected), right = Unpooled.wrappedBuffer(actual);
        try {
            assertTrue(sameValue(codec.decode(left), codec.decode(right)), name + " changed decoded wire values");
            assertFalse(left.isReadable(), name); assertFalse(right.isReadable(), name);
        } finally { left.release(); right.release(); }
    }
    private static boolean sameValue(Object left, Object right) throws Exception {
        if (left == null || right == null) return left == right;
        if (!left.getClass().equals(right.getClass())) return false;
        if (left instanceof java.util.Map<?, ?> a && right instanceof java.util.Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object key : a.keySet()) if (!sameValue(a.get(key), b.get(key))) return false;
            return true;
        }
        if (left instanceof java.util.List<?> a && right instanceof java.util.List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameValue(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left instanceof java.util.Optional<?> a && right instanceof java.util.Optional<?> b) return sameValue(a.orElse(null), b.orElse(null));
        if (left.getClass().isRecord()) {
            for (var component : left.getClass().getRecordComponents()) if (!sameValue(component.getAccessor().invoke(left), component.getAccessor().invoke(right))) return false;
            return true;
        }
        return java.util.Objects.deepEquals(left, right);
    }
    private static Object worldRegistries(ClassLoader loader, Object builtIns, java.util.Map<String, com.google.gson.JsonObject> models) throws Exception {
        Object provider = loader.loadClass("net.minecraft.data.registries.VanillaRegistries").getMethod("createWorldLookup").invoke(null);
        Class<?> providerType = loader.loadClass("net.minecraft.core.HolderLookup$Provider");
        Class<?> lookupType = loader.loadClass("net.minecraft.core.HolderLookup");
        Class<?> resourceKey = loader.loadClass("net.minecraft.resources.ResourceKey");
        Class<?> holder = loader.loadClass("net.minecraft.core.Holder$Reference");
        Class<?> mapped = loader.loadClass("net.minecraft.core.MappedRegistry");
        Class<?> lifecycle = loader.loadClass("com.mojang.serialization.Lifecycle");
        Class<?> registration = loader.loadClass("net.minecraft.core.RegistrationInfo");
        Object stable = lifecycle.getMethod("stable").invoke(null);
        Object builtIn = registration.getField("BUILT_IN").get(null);
        java.util.Map<Object, Object> registries = new java.util.LinkedHashMap<>();
        Class<?> registryType = loader.loadClass("net.minecraft.core.Registry");
        for (Object registry : (Iterable<?>) builtIns) registries.put(registryType.getMethod("key").invoke(registry), registry);
        java.util.Set<Object> required = new java.util.HashSet<>();
        var registryFields = loader.loadClass("net.minecraft.core.registries.Registries");
        var pattern = java.util.regex.Pattern.compile("Registries\\.([A-Z_]+)");
        for (var model : models.values()) {
            var matches = pattern.matcher(model.toString());
            while (matches.find()) required.add(registryFields.getField(matches.group(1)).get(null));
        }
        for (Object key : required) {
                if (registries.containsKey(key)) continue;
                Object lookup = providerType.getMethod("lookupOrThrow", resourceKey).invoke(provider, key);
                Object registry = mapped.getConstructor(resourceKey, lifecycle).newInstance(key, stable);
                try (Stream<?> elements = (Stream<?>) lookupType.getMethod("listElements").invoke(lookup)) {
                    for (Object reference : elements.toList()) mapped.getMethod("register", resourceKey, Object.class, registration).invoke(registry, holder.getMethod("key").invoke(reference), holder.getMethod("value").invoke(reference), builtIn);
                }
                mapped.getMethod("freeze").invoke(registry); registries.put(key, registry);
        }
        return loader.loadClass("net.minecraft.core.RegistryAccess$ImmutableRegistryAccess").getConstructor(java.util.Map.class).newInstance(registries);
    }
    private static Class<?> loadOriginal(ClassLoader loader, String name) throws ClassNotFoundException {
        String candidate = name;
        while (true) {
            try { return loader.loadClass(candidate); }
            catch (ClassNotFoundException e) {
                int dot = candidate.lastIndexOf('.'); if (dot < 0) throw e;
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }
    private static byte[] body(Packet packet) {
        ByteBuf buffer = Unpooled.buffer();
        try { packet.serialize(buffer); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes; }
        finally { buffer.release(); }
    }
}
