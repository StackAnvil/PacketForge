package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.ComponentPatch;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.protocol.packet.game.ClientboundSystemChatPacket;
import org.stackanvil.packetforge.protocol.packet.login.ClientboundLoginDisconnectPacket;

class AdventureApiTest {
    @Test
    void constructsPacketsAndItemNamesDirectlyWithAdventureValues() {
        Component text = Component.text("Adventure").decorate(TextDecoration.BOLD);
        var patch = ComponentPatch.builder()
                .set(DataComponentTypes.CUSTOM_NAME, text)
                .build();
        assertEquals(text, patch.get(DataComponentTypes.CUSTOM_NAME).orElseThrow());
        var registry = GeneratedProtocol.registry();
        for (Packet packet :
                List.of(new ClientboundSystemChatPacket(text, false), new ClientboundLoginDisconnectPacket(text))) {
            var entry = registry.entries().values().stream()
                    .filter(value -> value.type().isInstance(packet))
                    .findFirst()
                    .orElseThrow();
            var body = Unpooled.buffer();
            try {
                registry.encode(entry.key(), packet, body);
                assertEquals(
                        packet, registry.decode(entry.key().state(), entry.key().flow(), body));
                assertFalse(body.isReadable());
            } finally {
                body.release();
            }
        }
    }

    @Test
    void everyExtractedSemanticTextFieldExposesAdventureTypes() throws Exception {
        try (var reader = new InputStreamReader(
                getClass().getResourceAsStream("/packetforge/protocol.json"), StandardCharsets.UTF_8)) {
            var report = JsonParser.parseReader(reader).getAsJsonObject();
            int count = 0;
            for (var model : report.getAsJsonArray("models")) {
                for (var value : model.getAsJsonObject().getAsJsonArray("fields")) {
                    var field = value.getAsJsonObject();
                    var read = field.getAsJsonObject("read");
                    String shape = read.get("shape").getAsString();
                    if (shape.contains("ComponentSerialization") || shape.contains("Style.Serializer")) {
                        assertTrue(
                                read.get("type").getAsString().contains("net.kyori.adventure.text."), model.toString());
                        assertTrue(
                                field.getAsJsonObject("write")
                                        .get("type")
                                        .getAsString()
                                        .contains("net.kyori.adventure.text."),
                                model.toString());
                        count++;
                    }
                }
            }
            assertEquals(46, count);
        }
    }
}
