package org.stackanvil.packetforge.commons.status;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.JsonCodecs;
import org.stackanvil.packetforge.commons.text.JsonDialog;

class StatusCodecsTest {
    private static final org.stackanvil.packetforge.commons.PacketCodec<ServerStatus> CODEC =
            StatusCodecs.status(JsonCodecs.json(32767));

    @Test
    void typedValuesRetainRichAdventureDescriptionsAndPlayerInformation() {
        var text = Component.text("Server")
                .clickEvent(ClickEvent.showDialog(
                        new JsonDialog(JsonParser.parseString("{\"type\":\"minecraft:notice\",\"title\":\"hello\"}")
                                .getAsJsonObject())));
        var value = new ServerStatus(
                text,
                Optional.of(
                        new ServerStatus.Players(20, 2, List.of(new ServerStatus.Player(new UUID(1, 2), "player")))),
                Optional.of(new ServerStatus.Version("26.3", 777)),
                Optional.of(new ServerStatus.Favicon(new byte[] {1, 2, 3})),
                true);
        var buffer = Unpooled.buffer();
        try {
            CODEC.encode(buffer, value);
            assertEquals(value, CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedOptionalFieldsUseSourceDefaultsWithoutDiscardingOtherFields() {
        var value = read(
                "{\"description\":false,\"players\":{\"max\":20,\"online\":2,\"sample\":[{\"id\":\"bad uuid\",\"name\":\"name\"}]},\"version\":{\"name\":42,\"protocol\":777},\"favicon\":\"bad\",\"enforcesSecureChat\":true}");
        assertEquals(Component.empty(), value.description());
        assertEquals(new ServerStatus.Players(20, 2), value.players().orElseThrow());
        assertTrue(value.version().isEmpty());
        assertTrue(value.favicon().isEmpty());
        assertTrue(value.enforcesSecureChat());
        assertTrue(read("{\"players\":{\"max\":true,\"online\":1}}").players().isEmpty());
        assertEquals(
                new ServerStatus.Players(20, 1),
                read("{\"players\":{\"max\":20.9,\"online\":1.1}}").players().orElseThrow());
    }

    @Test
    void relativeNbtCoordinatesRemainAdventurePositionsAndRetainTheirSourceText() {
        for (String source : List.of("~ ~ ~", "^ ^ ^")) {
            var status = read("{\"description\":{\"nbt\":\"path\",\"block\":\"" + source + "\"}}");
            var component = assertInstanceOf(net.kyori.adventure.text.BlockNBTComponent.class, status.description());
            assertEquals(source, component.pos().asString());
            if (source.startsWith("~"))
                assertInstanceOf(net.kyori.adventure.text.BlockNBTComponent.WorldPos.class, component.pos());
            else assertInstanceOf(net.kyori.adventure.text.BlockNBTComponent.LocalPos.class, component.pos());
            var buffer = Unpooled.buffer();
            try {
                CODEC.encode(buffer, status);
                assertEquals(status, CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void faviconValuesAreImmutableAndCanonicalizeNewlinesInBase64() {
        byte[] bytes = {1, 2};
        var icon = new ServerStatus.Favicon(bytes);
        bytes[0] = 99;
        icon.iconBytes()[0] = 98;
        assertArrayEquals(new byte[] {1, 2}, icon.iconBytes());
        assertEquals(
                icon,
                read("{\"favicon\":\"data:image/png;base64,AQ\\nI=\"}")
                        .favicon()
                        .orElseThrow());
    }

    @Test
    void retainsTheOuterJsonLimitAndRejectsNonObjectResponses() {
        for (String json : List.of("[]", "false", "null", "42"))
            assertThrows(IllegalArgumentException.class, () -> read(json));
        var buffer = Unpooled.buffer();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CODEC.encode(buffer, new ServerStatus(Component.text("a".repeat(32767)))));
        } finally {
            buffer.release();
        }
    }

    private static ServerStatus read(String json) {
        var input = Unpooled.buffer();
        try {
            JsonCodecs.json(32767).encode(input, JsonParser.parseString(json));
            return CODEC.decode(input);
        } finally {
            input.release();
        }
    }
}
