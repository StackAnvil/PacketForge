package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.stackanvil.packetforge.commons.JsonCodecs;
import org.stackanvil.packetforge.protocol.packet.status.ClientboundStatusResponsePacket;

/** Compares typed status normalization with the isolated vanilla status codec. */
final class VanillaStatusAudit {
    private VanillaStatusAudit() {}

    static void run(ClassLoader loader, Class<?> byteBuf, Class<?> unpooled, Method decode, Method encode)
            throws Exception {
        Object source = loader.loadClass("net.minecraft.network.protocol.status.ClientboundStatusResponsePacket")
                .getField("STREAM_CODEC")
                .get(null);
        var cases = new java.util.ArrayList<>(List.of(
                "{}",
                "{\"description\":\"hello\"}",
                "{\"description\":{\"text\":\"hello\",\"bold\":true}}",
                "{\"description\":[],\"enforcesSecureChat\":true}",
                "{\"description\":false}",
                "{\"description\":null}",
                "{\"description\":{\"text\":\"dialog\",\"click_event\":{\"action\":\"show_dialog\",\"dialog\":{\"type\":\"minecraft:notice\",\"title\":\"title\"}}}}",
                "{\"players\":{\"max\":20,\"online\":2}}",
                "{\"players\":{\"max\":20.5,\"online\":1.2}}",
                "{\"players\":{\"max\":true,\"online\":1}}",
                "{\"players\":{\"max\":20,\"online\":2,\"sample\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"player\"}]}}",
                "{\"players\":{\"max\":20,\"online\":2,\"sample\":[{\"id\":\"bad\",\"name\":\"name\"}]}}",
                "{\"players\":{\"max\":20,\"online\":2,\"sample\":true}}",
                "{\"players\":{\"max\":20}}",
                "{\"version\":{\"name\":\"26.3\",\"protocol\":777}}",
                "{\"version\":{\"name\":42,\"protocol\":777}}",
                "{\"favicon\":\"data:image/png;base64,AQ\\nI=\"}",
                "{\"favicon\":\"wrong prefix\"}",
                "{\"favicon\":\"data:image/png;base64,!!!!\"}",
                "{\"enforcesSecureChat\":1}",
                "{\"enforcesSecureChat\":true}",
                "{\"description\":\"\",\"enforcesSecureChat\":false}",
                "[]",
                "null",
                "false",
                "42"));
        for (String description : List.of(
                "[\"\"]",
                "[\"first\",{\"text\":\"second\",\"bold\":true}]",
                "{\"translate\":\"sample\",\"with\":[true,3.5,\"argument\"]}",
                "{\"keybind\":\"key.jump\",\"extra\":[\"extra\"]}",
                "{\"score\":{\"name\":\"player\",\"objective\":\"objective\"}}",
                "{\"selector\":\"@a\",\"separator\":\",\"}",
                "{\"nbt\":\"foo\",\"storage\":\"test:data\",\"plain\":true}",
                "{\"nbt\":\"foo\",\"block\":\"~ ~ ~\"}",
                "{\"nbt\":\"foo\",\"block\":\"^ ^ ^\"}",
                "{\"text\":\"font\",\"font\":\"minecraft:default\",\"shadow_color\":1234}",
                "{\"text\":\"click\",\"click_event\":{\"action\":\"custom\",\"id\":\"test:action\",\"payload\":{\"flag\":true}}}",
                "{\"text\":\"item\",\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"name\"}}}",
                "{\"type\":\"keybind\",\"keybind\":\"key.jump\",\"text\":\"ignored\"}")) {
            cases.add("{\"description\":" + description + "}");
        }
        for (String coordinates : List.of(
                "~.5 ~1. ~-2.25",
                "^-.5 ^.5 ^",
                " 0 0",
                "0  0",
                "0 0 ",
                "~  ~ ~",
                "~\t~ ~",
                "^ ^ 0",
                "2147483648 0 0",
                "~2147483648 ~ ~",
                "^1e2 ^ ^",
                "0 0 0 trailing")) {
            var description = new com.google.gson.JsonObject();
            description.addProperty("nbt", "path");
            description.addProperty("block", coordinates);
            cases.add("{\"description\":" + description + "}");
        }
        cases.add("{\"description\":\"" + "a".repeat(32749) + "\"}");
        for (String json : cases) {
            var body = Unpooled.buffer();
            var output = Unpooled.buffer();
            Object nativeInput = null, nativeOutput = null;
            try {
                JsonCodecs.json(32767).encode(body, JsonParser.parseString(json));
                byte[] bytes = new byte[body.readableBytes()];
                body.getBytes(0, bytes);
                nativeInput = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
                Object value;
                try {
                    value = decode.invoke(source, nativeInput);
                } catch (InvocationTargetException invalid) {
                    assertEquals(
                            "io.netty.handler.codec.DecoderException",
                            invalid.getCause().getClass().getName());
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> ClientboundStatusResponsePacket.CODEC.decode(body),
                            json);
                    continue;
                }
                var generated = ClientboundStatusResponsePacket.CODEC.decode(body);
                ClientboundStatusResponsePacket.CODEC.encode(output, generated);
                nativeOutput = unpooled.getMethod("buffer").invoke(null);
                encode.invoke(source, nativeOutput, value);
                byte[] nativeBytes =
                        new byte[(int) byteBuf.getMethod("readableBytes").invoke(nativeOutput)];
                byteBuf.getMethod("readBytes", byte[].class).invoke(nativeOutput, (Object) nativeBytes);
                var nativeBody = Unpooled.wrappedBuffer(nativeBytes);
                try {
                    JsonElement expected = JsonCodecs.json(32767).decode(nativeBody);
                    JsonElement actual = JsonCodecs.json(32767).decode(output);
                    assertEquals(expected, actual, json);
                    assertFalse(output.isReadable());
                    assertFalse(body.isReadable());
                } finally {
                    nativeBody.release();
                }
            } finally {
                body.release();
                output.release();
                if (nativeInput != null) byteBuf.getMethod("release").invoke(nativeInput);
                if (nativeOutput != null) byteBuf.getMethod("release").invoke(nativeOutput);
            }
        }
        var oversized = Unpooled.buffer();
        Object nativeInput = null, nativeOutput = null;
        try {
            org.stackanvil.packetforge.commons.WireCodecs.string(32768)
                    .encode(oversized, "{\"description\":\"" + "a".repeat(32750) + "\"}");
            byte[] bytes = new byte[oversized.readableBytes()];
            oversized.getBytes(0, bytes);
            nativeInput = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
            Object input = nativeInput;
            var invalid = assertThrows(InvocationTargetException.class, () -> decode.invoke(source, input));
            assertEquals(
                    "io.netty.handler.codec.DecoderException",
                    invalid.getCause().getClass().getName());
            assertThrows(IllegalArgumentException.class, () -> ClientboundStatusResponsePacket.CODEC.decode(oversized));
            var nativeComponent = loader.loadClass("net.minecraft.network.chat.Component");
            Object description =
                    nativeComponent.getMethod("literal", String.class).invoke(null, "a".repeat(32750));
            var nativeStatus = loader.loadClass("net.minecraft.network.protocol.status.ServerStatus");
            Object status = nativeStatus
                    .getConstructor(
                            nativeComponent,
                            java.util.Optional.class,
                            java.util.Optional.class,
                            java.util.Optional.class,
                            boolean.class)
                    .newInstance(
                            description,
                            java.util.Optional.empty(),
                            java.util.Optional.empty(),
                            java.util.Optional.empty(),
                            false);
            Object packet = loader.loadClass("net.minecraft.network.protocol.status.ClientboundStatusResponsePacket")
                    .getConstructor(nativeStatus)
                    .newInstance(status);
            nativeOutput = unpooled.getMethod("buffer").invoke(null);
            Object output = nativeOutput;
            assertThrows(InvocationTargetException.class, () -> encode.invoke(source, output, packet));
            var generated =
                    new ClientboundStatusResponsePacket(new org.stackanvil.packetforge.commons.status.ServerStatus(
                            net.kyori.adventure.text.Component.text("a".repeat(32750))));
            oversized.clear();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ClientboundStatusResponsePacket.CODEC.encode(oversized, generated));
        } finally {
            oversized.release();
            if (nativeInput != null) byteBuf.getMethod("release").invoke(nativeInput);
            if (nativeOutput != null) byteBuf.getMethod("release").invoke(nativeOutput);
        }
        assertEquals(52, cases.size());
        System.out.println("Verified " + (cases.size() + 2) + " status cases against vanilla");
    }
}
