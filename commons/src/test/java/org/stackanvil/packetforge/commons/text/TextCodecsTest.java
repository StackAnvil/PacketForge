package org.stackanvil.packetforge.commons.text;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.JsonCodecs;
import org.stackanvil.packetforge.commons.NbtCodecs;
import org.stackanvil.packetforge.commons.NbtTag;
import org.stackanvil.packetforge.commons.PacketCodec;

class TextCodecsTest {
    @Test
    void roundTripsRichTextAndExplicitStyleStates() {
        var style = Style.style()
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
                .shadowColor(ShadowColor.shadowColor(0x80123456))
                .font(Key.key("test:font"))
                .insertion("insert")
                .clickEvent(ClickEvent.runCommand("/help"))
                .hoverEvent(HoverEvent.showText(Component.text("hint")))
                .build();
        assertEquals(style, roundTrip(TextCodecs.STYLE, style));
        assertEquals(style, roundTrip(TextCodecs.TRUSTED_STYLE, style));
        assertEquals(style, roundTrip(TextCodecs.jsonStyle(JsonCodecs.json(262144)), style));
        for (var component : List.<Component>of(
                Component.empty(),
                Component.text("plain"),
                Component.text("styled").style(style).append(Component.keybind("key.jump")),
                Component.translatable()
                        .key("test.message")
                        .fallback("fallback")
                        .arguments(
                                TranslationArgument.numeric(123),
                                TranslationArgument.component(
                                        Component.text("arg").color(NamedTextColor.RED)))
                        .build(),
                Component.score("player", "objective"),
                Component.selector("@a", Component.text(", ")),
                Component.object(ObjectContents.sprite(Key.key("minecraft:gui"), Key.key("test:sprite"))))) {
            assertEquals(component, roundTrip(TextCodecs.COMPONENT, component));
            assertEquals(component, roundTrip(TextCodecs.TRUSTED_COMPONENT, component));
            assertEquals(
                    JsonTextSerializer.write(component),
                    JsonTextSerializer.write(roundTrip(TextCodecs.json(JsonCodecs.json(262144)), component)));
        }
    }

    @Test
    void preservesCustomClickPayloadTagTypesInsteadOfDiscardingThem() {
        var payload = new NbtTag.Compound(Map.of(
                "bytes", new NbtTag.ByteArray(new byte[] {1, -1}),
                "ints", new NbtTag.IntArray(new int[] {1, -1}),
                "longs", new NbtTag.LongArray(new long[] {Long.MIN_VALUE}),
                "short", new NbtTag.ShortValue((short) 12),
                "float", new NbtTag.FloatValue(1.25f)));
        var event = ClickEvent.custom(
                Key.key("test:click"), BinaryTagHolder.binaryTagHolder(TextNbt.toSnbt(TextNbt.toValue(payload))));
        var value = Component.text("click")
                .clickEvent(event)
                .append(Component.text("nested").clickEvent(event));
        var decoded = roundTrip(TextCodecs.COMPONENT, value);
        assertEquals(value, decoded);
        var custom = (ClickEvent.Payload.Custom) decoded.clickEvent().payload();
        assertEquals(payload, TextNbt.fromValue(TextNbt.fromSnbt(custom.nbt().string())));
        var noPayload = Component.text("empty payload").clickEvent(ClickEvent.custom(Key.key("test:empty")));
        assertEquals(noPayload, roundTrip(TextCodecs.COMPONENT, noPayload));
    }

    @Test
    void preservesHoverItemValuesRemovalsAndEntityNames() {
        var item = Component.text("item")
                .hoverEvent(HoverEvent.showItem(
                        Key.key("minecraft:stone"),
                        2,
                        Map.of(
                                Key.key("minecraft:custom_name"),
                                new NbtDataComponentValue(new NbtTag.StringValue("Stone")),
                                Key.key("minecraft:lore"),
                                DataComponentValue.removed())));
        assertEquals(item, roundTrip(TextCodecs.COMPONENT, item));
        var entity = Component.text("entity")
                .hoverEvent(HoverEvent.showEntity(
                        Key.key("minecraft:pig"),
                        new UUID(1, 2),
                        Component.text("name").decorate(TextDecoration.BOLD)));
        assertEquals(entity, roundTrip(TextCodecs.COMPONENT, entity));
        assertEquals(entity, roundTrip(TextCodecs.json(JsonCodecs.json(262144)), entity));
    }

    @Test
    void retainsJsonBooleansNestedCustomEventsAndInlineDialogs() {
        var codec = TextCodecs.json(JsonCodecs.json(262144));
        for (String json : List.of(
                "{\"translate\":\"test\",\"with\":[true,3,\"text\"]}",
                "{\"text\":\"click\",\"click_event\":{\"action\":\"custom\",\"id\":\"test:custom\",\"payload\":{\"flag\":true,\"values\":[1,2]}}}",
                "{\"text\":\"dialog\",\"click_event\":{\"action\":\"show_dialog\",\"dialog\":{\"type\":\"minecraft:notice\",\"title\":\"title\",\"pause\":false}}}")) {
            var input = Unpooled.buffer();
            try {
                JsonCodecs.json(262144).encode(input, JsonParser.parseString(json));
                var value = codec.decode(input);
                assertEquals(value, roundTrip(codec, value));
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
        var dialog = Component.text("dialog").clickEvent(ClickEvent.showDialog(new NbtDialog(Key.key("test:dialog"))));
        assertEquals(dialog, roundTrip(codec, dialog));
        assertEquals(dialog, roundTrip(TextCodecs.COMPONENT, dialog));
        var inline = Component.text("inline")
                .clickEvent(ClickEvent.showDialog(new NbtDialog(new NbtTag.Compound(Map.of(
                        "type",
                        new NbtTag.StringValue("minecraft:notice"),
                        "title",
                        new NbtTag.StringValue("title"))))));
        assertEquals(inline, roundTrip(TextCodecs.COMPONENT, inline));
    }

    @Test
    void rejectsInvalidTextRootsAndHonorsJsonCharacterLimits() {
        for (NbtTag invalid :
                List.of(new NbtTag.IntValue(12), new NbtTag.ListValue(0, List.of()), new NbtTag.Compound(Map.of()))) {
            var buffer = Unpooled.buffer();
            try {
                NbtCodecs.TAG.encode(buffer, invalid);
                assertThrows(IllegalArgumentException.class, () -> TextCodecs.COMPONENT.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        var buffer = Unpooled.buffer();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TextCodecs.json(JsonCodecs.json(4)).encode(buffer, Component.text("too long")));
        } finally {
            buffer.release();
        }
    }

    @Test
    void reusesJsonTranslationNumbersInNbtTextWithoutAConversionLayer() {
        var buffer = Unpooled.buffer();
        try {
            JsonCodecs.json(262144)
                    .encode(buffer, JsonParser.parseString("{\"translate\":\"test\",\"with\":[3,1.25]}"));
            var value = TextCodecs.json(JsonCodecs.json(262144)).decode(buffer);
            var decoded = (net.kyori.adventure.text.TranslatableComponent) roundTrip(TextCodecs.COMPONENT, value);
            assertEquals(3, ((Number) decoded.arguments().get(0).value()).intValue());
            assertEquals(1.25, ((Number) decoded.arguments().get(1).value()).doubleValue());
        } finally {
            buffer.release();
        }
    }

    private static <T> T roundTrip(PacketCodec<T> codec, T value) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, value);
            T decoded = codec.decode(buffer);
            assertFalse(buffer.isReadable());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
