package org.stackanvil.packetforge.protocol;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundCommandSuggestionPacket;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundSetCreativeModeSlotPacket;
import static org.junit.jupiter.api.Assertions.*;

/** Differential checks for named codecs, context policies, and intentionally discarded custom data. */
final class VanillaVariantAudit {
    private final ClassLoader loader;
    private final Map<String, JsonObject> models;
    private final Object registries;
    private final Class<?> byteBuf, unpooled, friendly, registryAccess;
    private final Method decode, encode;
    private int compared;
    private VanillaVariantAudit(ClassLoader loader, Map<String, JsonObject> models, Object registries, Class<?> byteBuf, Class<?> unpooled, Class<?> friendly, Class<?> registryAccess, Method decode, Method encode) {
        this.loader = loader; this.models = models; this.registries = registries; this.byteBuf = byteBuf; this.unpooled = unpooled; this.friendly = friendly; this.registryAccess = registryAccess; this.decode = decode; this.encode = encode;
    }
    static void run(ClassLoader loader, Map<String, JsonObject> models, Object registries, Class<?> byteBuf, Class<?> unpooled, Class<?> friendly, Class<?> registryAccess, Method decode, Method encode) throws Exception {
        var audit = new VanillaVariantAudit(loader, models, registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
        audit.named(); audit.fallbacks(); audit.context();
        System.out.println("Verified " + audit.compared + " named-codec, context, and custom-payload boundary cases against vanilla");
    }
    private Object input(Object raw) throws Exception { return friendly.getConstructor(byteBuf, registryAccess).newInstance(raw, registries); }
    private Object source(JsonObject model) throws Exception { return loader.loadClass(model.get("originalName").getAsString().split("#", 2)[0]).getField(model.get("sourceCodecField").getAsString()).get(null); }
    private byte[] body(PacketCodec<Object> codec, Object value) {
        ByteBuf output = Unpooled.buffer();
        try { codec.encode(output, value); byte[] bytes = new byte[output.readableBytes()]; output.readBytes(bytes); return bytes; }
        finally { output.release(); }
    }
    private byte[] sourceEncode(Object codec, Object value) throws Exception {
        Object output = unpooled.getMethod("buffer").invoke(null);
        try { encode.invoke(codec, input(output), value); byte[] bytes = new byte[(int) byteBuf.getMethod("readableBytes").invoke(output)]; byteBuf.getMethod("readBytes", byte[].class).invoke(output, (Object) bytes); return bytes; }
        finally { byteBuf.getMethod("release").invoke(output); }
    }
    private Object sourceDecode(Object codec, byte[] bytes) throws Exception {
        Object raw = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
        try { Object value = decode.invoke(codec, input(raw)); assertEquals(0, byteBuf.getMethod("readableBytes").invoke(raw)); return value; }
        finally { byteBuf.getMethod("release").invoke(raw); }
    }
    private void named() throws Exception {
        for (var model : models.values()) {
            if (!model.has("packet") || !model.get("packet").getAsBoolean() || model.get("sourceCodecField").getAsString().equals("STREAM_CODEC")) continue;
            Class<?> type = Class.forName(model.get("generatedName").getAsString());
            @SuppressWarnings("unchecked") PacketCodec<Object> generated = (PacketCodec<Object>) type.getField("CODEC").get(null);
            Object source = source(model);
            for (int variant = 0; variant < 3; variant++) {
                byte[] expected = body(generated, GeneratedRoundTripTest.sample(type, "", variant, models));
                byte[] actual = sourceEncode(source, sourceDecode(source, expected));
                if (!Arrays.equals(expected, actual)) VanillaCompatibilityTest.assertSameWireValues(generated, expected, actual, model.get("originalName").getAsString());
                compared++;
            }
        }
    }
    private void fallbacks() throws Exception {
        for (var definition : models.values()) {
            if (!definition.has("fallback")) continue;
            String name = definition.get("name").getAsString();
            @SuppressWarnings("unchecked") PacketCodec<Object> generated = (PacketCodec<Object>) GeneratedWireCodecs.class.getField(name).get(null);
            Object source = loader.loadClass(definition.get("sourceOwner").getAsString()).getField(definition.get("sourceCodecField").getAsString()).get(null);
            String shape = definition.getAsJsonObject("fallback").get("shape").getAsString(); int maximum = Integer.parseInt(shape.substring(shape.indexOf('(') + 1, shape.indexOf(')')));
            String channel = "packetforge:unknown";
            for (int length : new int[]{0, 1, maximum, maximum + 1}) {
                byte[] data = new byte[length]; Arrays.fill(data, (byte) 0xa5); var value = new Variant<>(channel, data);
                ByteBuf raw = Unpooled.buffer(); byte[] bytes;
                try { WireCodecs.IDENTIFIER.encode(raw, channel); raw.writeBytes(data); bytes = new byte[raw.readableBytes()]; raw.readBytes(bytes); }
                finally { raw.release(); }
                if (length > maximum) {
                    assertThrows(IllegalArgumentException.class, () -> body(generated, value));
                    ByteBuf input = Unpooled.wrappedBuffer(bytes); try { assertThrows(IllegalArgumentException.class, () -> generated.decode(input)); } finally { input.release(); }
                    assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceDecode(source, bytes)); compared++; continue;
                }
                assertArrayEquals(bytes, body(generated, value));
                ByteBuf input = Unpooled.wrappedBuffer(bytes);
                try { var parsed = (Variant<?>) generated.decode(input); assertEquals(channel, parsed.type()); assertArrayEquals(data, (byte[]) parsed.value()); assertFalse(input.isReadable()); }
                finally { input.release(); }
                Object packet = sourceDecode(source, bytes), payload = packet.getClass().getMethod("payload").invoke(packet);
                Object type = loader.loadClass("net.minecraft.network.protocol.common.custom.CustomPacketPayload").getMethod("type").invoke(payload);
                Object id = loader.loadClass("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type").getMethod("id").invoke(type); assertEquals(channel, id.toString());
                ByteBuf identifier = Unpooled.buffer();
                try { WireCodecs.IDENTIFIER.encode(identifier, channel); byte[] expected = new byte[identifier.readableBytes()]; identifier.readBytes(expected); assertArrayEquals(expected, sourceEncode(source, packet), "Vanilla intentionally discards unknown data"); }
                finally { identifier.release(); }
                compared++;
            }
            // Known codecs keep their own limits, even when their payload exceeds the fallback limit.
            var branch = definition.getAsJsonArray("cases").get(0).getAsJsonObject(); Class<?> brand = (Class<?>) GeneratedRoundTripTest.type(branch.getAsJsonObject("value").get("type").getAsString());
            Object payload = brand.getConstructor(String.class).newInstance("é".repeat(30000));
            byte[] bytes = body(generated, new Variant<>(branch.get("key").getAsString(), payload));
            assertArrayEquals(bytes, sourceEncode(source, sourceDecode(source, bytes))); compared++;
        }
    }
    private void context() throws Exception {
        Class<?> packetType = loader.loadClass("net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket");
        Object full = packetType.getField("STREAM_CODEC").get(null), chat = packetType.getField("CHAT_ONLY_STREAM_CODEC").get(null);
        for (boolean commands : new boolean[]{false, true}) for (int length : new int[]{256, 257, 32500, 32501}) {
            PacketCodec<Object> generated = cast(commands ? ServerboundCommandSuggestionPacket.CODEC : ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC.CODEC);
            Object value = commands ? new ServerboundCommandSuggestionPacket(7, "é".repeat(length)) : new ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC(7, "é".repeat(length));
            Object vanilla = packetType.getConstructor(int.class, String.class).newInstance(7, "é".repeat(length)), source = commands ? full : chat;
            if (length <= 32500) assertArrayEquals(body(generated, value), sourceEncode(source, vanilla));
            else { assertThrows(IllegalArgumentException.class, () -> body(generated, value)); assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceEncode(source, vanilla)); }
            ByteBuf buffer = Unpooled.buffer(); byte[] bytes;
            try { WireCodecs.VAR_INT.encode(buffer, 7); WireCodecs.STRING.encode(buffer, "é".repeat(length)); bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); }
            finally { buffer.release(); }
            ByteBuf input = Unpooled.wrappedBuffer(bytes);
            try {
                if (length <= (commands ? 32500 : 256)) { generated.decode(input); sourceDecode(source, bytes); assertFalse(input.isReadable()); }
                else { assertThrows(IllegalArgumentException.class, () -> generated.decode(input)); var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceDecode(source, bytes)); if (!commands) assertEquals("net.minecraft.network.SkipPacketDecoderException", rejected.getCause().getClass().getName()); }
            } finally { input.release(); }
            compared++;
        }
        for (byte[] malformed : new byte[][]{new byte[0], {(byte) 0x80}, {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}, {0, (byte) 0x80}, {0, 1}, {0, -1, -1, -1, -1, 15}}) {
            var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceDecode(chat, malformed));
            ByteBuf input = Unpooled.wrappedBuffer(malformed);
            try {
                var failure = assertThrows(RuntimeException.class, () -> ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC.CODEC.decode(input));
                assertEquals(rejected.getCause().getClass().getName().equals("net.minecraft.network.SkipPacketDecoderException"), failure instanceof SkippedPacketException, "Chat decoder skip policy for " + Arrays.toString(malformed)); compared++;
            } finally { input.release(); }
        }
        for (byte[] malformedUtf8 : new byte[][]{{0, 2, (byte) 0xc0, (byte) 0x80}, {0, 1, (byte) 0xed}, {0, 3, (byte) 0xe0, (byte) 0x80, (byte) 0x80}, {0, 4, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x83}}) {
            ByteBuf input = Unpooled.wrappedBuffer(malformedUtf8);
            try {
                var value = ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC.CODEC.decode(input); Object original = sourceDecode(chat, malformedUtf8);
                assertEquals(value.command(), packetType.getMethod("command").invoke(original));
                assertArrayEquals(body(cast(ServerboundCommandSuggestionPacket_CHAT_ONLY_STREAM_CODEC.CODEC), value), sourceEncode(chat, original)); assertFalse(input.isReadable()); compared++;
            } finally { input.release(); }
        }
        Class<?> protocols = loader.loadClass("net.minecraft.network.protocol.game.GameProtocols"), context = loader.loadClass("net.minecraft.network.protocol.game.GameProtocols$Context");
        var infinite = new AtomicBoolean(false);
        Object flags = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{context}, (proxy, method, arguments) -> method.getName().equals("hasInfiniteMaterials") ? infinite.get() : false);
        Object modifier = protocols.getField("HAS_INFINITE_MATERIALS").get(null);
        Class<?> streamCodec = loader.loadClass("net.minecraft.network.codec.StreamCodec");
        Class<?> creativePacket = loader.loadClass("net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket");
        Object original = creativePacket.getField("STREAM_CODEC").get(null);
        Object gate = loader.loadClass("net.minecraft.network.protocol.CodecModifier").getMethod("apply", streamCodec, Object.class).invoke(modifier, original, flags);
        Object empty = loader.loadClass("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
        Object packet = creativePacket.getConstructor(short.class, loader.loadClass("net.minecraft.world.item.ItemStack")).newInstance((short) 1, empty);
        byte[] bytes = body(cast(ServerboundSetCreativeModeSlotPacket.CODEC), new ServerboundSetCreativeModeSlotPacket((short) 1, ItemStack.EMPTY));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceDecode(gate, bytes)); assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceEncode(gate, packet)); compared++;
        infinite.set(true); assertArrayEquals(bytes, sourceEncode(gate, sourceDecode(gate, bytes))); compared++;
        byte[] invalid = body(cast(ServerboundSetCreativeModeSlotPacket.CODEC), new ServerboundSetCreativeModeSlotPacket((short) 1, new ItemStack(100, 1, ComponentPatch.EMPTY)));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> sourceDecode(gate, invalid)); compared++;
    }
    @SuppressWarnings("unchecked") private static PacketCodec<Object> cast(PacketCodec<?> codec) { return (PacketCodec<Object>) codec; }
}
