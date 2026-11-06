package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import org.stackanvil.packetforge.commons.JsonCodecs;
import org.stackanvil.packetforge.commons.NbtCodecs;
import org.stackanvil.packetforge.commons.NbtTag;
import org.stackanvil.packetforge.commons.PacketCodec;
import org.stackanvil.packetforge.commons.text.NbtDataComponentValue;
import org.stackanvil.packetforge.commons.text.NbtDialog;
import org.stackanvil.packetforge.commons.text.TextCodecs;

/** Rich Adventure values are compared with the isolated vanilla text codecs. */
final class VanillaTextAudit {
    private VanillaTextAudit() {}

    static void run(
            ClassLoader loader,
            Object registries,
            Class<?> byteBuf,
            Class<?> unpooled,
            Class<?> friendly,
            Class<?> registryAccess,
            Method decode,
            Method encode)
            throws Exception {
        var nativeWire = new NativeWire(registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
        int compared = 0;
        var values = samples();
        for (String field : List.of("STREAM_CODEC", "TRUSTED_STREAM_CODEC", "TRUSTED_CONTEXT_FREE_STREAM_CODEC")) {
            var codec = field.equals("STREAM_CODEC") ? TextCodecs.COMPONENT : TextCodecs.TRUSTED_COMPONENT;
            Object vanilla = loader.loadClass("net.minecraft.network.chat.ComponentSerialization")
                    .getField(field)
                    .get(null);
            for (var value : values) {
                nativeWire.compare(codec, body(codec, value), vanilla, false);
                compared++;
            }
        }
        var styleCodec = loader.loadClass("net.minecraft.network.chat.Style$Serializer")
                .getField("TRUSTED_STREAM_CODEC")
                .get(null);
        for (var value : values) {
            nativeWire.compare(
                    TextCodecs.TRUSTED_STYLE, body(TextCodecs.TRUSTED_STYLE, value.style()), styleCodec, false);
            compared++;
        }
        var json = TextCodecs.json(JsonCodecs.json(262144));
        var loginCodec = loader.loadClass("net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket")
                .getField("STREAM_CODEC")
                .get(null);
        for (String value : List.of(
                "\"simple\"",
                "[\"first\",{\"text\":\"second\",\"bold\":true}]",
                "{\"translate\":\"sample\",\"with\":[true,3.5,\"text\"]}",
                "{\"text\":\"custom\",\"click_event\":{\"action\":\"custom\",\"id\":\"test:click\",\"payload\":{\"flag\":true,\"data\":[1,2]}}}",
                "{\"text\":\"dialog\",\"click_event\":{\"action\":\"show_dialog\",\"dialog\":{\"type\":\"minecraft:notice\",\"title\":\"title\",\"pause\":false}}}",
                "{\"text\":\"item\",\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":{\"text\":\"name\",\"bold\":true}}}}")) {
            byte[] source = body(JsonCodecs.json(262144), JsonParser.parseString(value));
            nativeWire.compare(json, source, loginCodec, true);
            compared++;
        }
        var nativeNbt = loader.loadClass("net.minecraft.network.chat.ComponentSerialization")
                .getField("STREAM_CODEC")
                .get(null);
        for (NbtTag input : List.<NbtTag>of(
                new NbtTag.Compound(Map.of(
                        "translate", new NbtTag.StringValue("sample"), "with", new NbtTag.ByteArray(new byte[] {1, -1
                        }))),
                new NbtTag.Compound(Map.of(
                        "translate", new NbtTag.StringValue("sample"), "with", new NbtTag.IntArray(new int[] {1, -1}))),
                new NbtTag.Compound(
                        Map.of("translate", new NbtTag.StringValue("sample"), "with", new NbtTag.LongArray(new long[] {
                            Long.MIN_VALUE
                        }))),
                new NbtTag.Compound(
                        Map.of("nbt", new NbtTag.StringValue("foo"), "block", new NbtTag.StringValue("~ ~ ~"))),
                new NbtTag.Compound(
                        Map.of("nbt", new NbtTag.StringValue("foo"), "block", new NbtTag.StringValue("^ ^ ^"))),
                new NbtTag.Compound(Map.of(
                        "text",
                        new NbtTag.StringValue("flags"),
                        "bold",
                        new NbtTag.IntValue(1),
                        "shadow_color",
                        new NbtTag.ListValue(
                                3,
                                List.of(
                                        new NbtTag.IntValue(1),
                                        new NbtTag.IntValue(0),
                                        new NbtTag.IntValue(1),
                                        new NbtTag.IntValue(0))))),
                new NbtTag.Compound(Map.of(
                        "type",
                        new NbtTag.StringValue("keybind"),
                        "keybind",
                        new NbtTag.StringValue("key.jump"),
                        "text",
                        new NbtTag.StringValue("ignored"))),
                new NbtTag.Compound(Map.of(
                        "type",
                        new NbtTag.StringValue("nbt"),
                        "nbt",
                        new NbtTag.StringValue("foo"),
                        "source",
                        new NbtTag.StringValue("storage"),
                        "storage",
                        new NbtTag.StringValue("test:data"),
                        "entity",
                        new NbtTag.StringValue("@s"))),
                new NbtTag.Compound(Map.of(
                        "translate",
                        new NbtTag.StringValue("sample"),
                        "with",
                        new NbtTag.ListValue(1, List.of(new NbtTag.ByteValue((byte) 1))))))) {
            nativeWire.compare(TextCodecs.COMPONENT, body(NbtCodecs.TAG, input), nativeNbt, false);
            compared++;
        }
        System.out.println("Verified " + compared + " rich text, style, and event values against vanilla");
    }

    private static List<Component> samples() {
        var values = new ArrayList<Component>();
        values.add(Component.empty());
        values.add(Component.text("text café"));
        values.add(Component.text("root").append(Component.text("child").decorate(TextDecoration.BOLD)));
        values.add(Component.translatable()
                .key("sample.message")
                .fallback("fallback")
                .arguments(
                        TranslationArgument.numeric(12),
                        TranslationArgument.component(Component.text("argument").color(NamedTextColor.RED)))
                .build());
        values.add(Component.keybind("key.jump"));
        values.add(Component.score("player", "objective"));
        values.add(Component.selector("@a", Component.text(", ")));
        values.add(Component.entityNBT()
                .nbtPath("foo")
                .selector("@s")
                .interpret(true)
                .separator(Component.text(","))
                .build());
        values.add(Component.blockNBT()
                .nbtPath("foo")
                .pos(net.kyori.adventure.text.BlockNBTComponent.Pos.fromString("~0 ~0 ~0"))
                .plain(true)
                .build());
        values.add(Component.storageNBT()
                .nbtPath("foo")
                .storage(Key.key("test:data"))
                .build());
        values.add(Component.object(ObjectContents.sprite(Key.key("minecraft:gui"), Key.key("test:sprite"))));
        values.add(Component.object(ObjectContents.playerHead()
                .name("Player")
                .id(new UUID(1, 2))
                .hat(false)
                .profileProperty(
                        net.kyori.adventure.text.object.PlayerHeadObjectContents.property("textures", "sample", null))
                .build()));
        values.add(Component.text("style")
                .style(Style.style()
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.ITALIC, true)
                        .shadowColor(ShadowColor.shadowColor(0x80123456))
                        .font(Key.key("test:font"))
                        .insertion("insert")
                        .build()));
        for (var event : List.of(
                ClickEvent.openUrl("https://example.com/"),
                ClickEvent.runCommand("/help"),
                ClickEvent.suggestCommand("/help"),
                ClickEvent.changePage(2),
                ClickEvent.copyToClipboard("copy"),
                ClickEvent.custom(Key.key("test:click")),
                ClickEvent.custom(
                        Key.key("test:click"),
                        BinaryTagHolder.binaryTagHolder("{bytes:[B;1b,-1b],ints:[I;1,2],longs:[L;3L],float:1.5f}")),
                ClickEvent.showDialog(new NbtDialog(new NbtTag.Compound(Map.of(
                        "type",
                        new NbtTag.StringValue("minecraft:notice"),
                        "title",
                        new NbtTag.StringValue("title"),
                        "pause",
                        new NbtTag.ByteValue((byte) 0)))))))
            values.add(Component.text("click").clickEvent(event));
        values.add(Component.text("hover")
                .hoverEvent(HoverEvent.showText(Component.text("hint").decorate(TextDecoration.BOLD))));
        values.add(Component.text("entity")
                .hoverEvent(HoverEvent.showEntity(Key.key("minecraft:pig"), new UUID(1, 2), Component.text("name"))));
        values.add(Component.text("item")
                .hoverEvent(HoverEvent.showItem(
                        Key.key("minecraft:stone"),
                        2,
                        Map.of(
                                Key.key("minecraft:custom_name"),
                                new NbtDataComponentValue(new NbtTag.StringValue("Stone")),
                                Key.key("minecraft:lore"),
                                DataComponentValue.removed()))));
        return values;
    }

    private record NativeWire(
            Object registries,
            Class<?> byteBuf,
            Class<?> unpooled,
            Class<?> friendly,
            Class<?> registryAccess,
            Method decode,
            Method encode) {
        private <T> void compare(PacketCodec<T> codec, byte[] expected, Object vanilla, boolean json) throws Exception {
            Object input = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) expected);
            Object output = unpooled.getMethod("buffer").invoke(null);
            try {
                Object nativeInput =
                        friendly.getConstructor(byteBuf, registryAccess).newInstance(input, registries);
                Object nativeOutput =
                        friendly.getConstructor(byteBuf, registryAccess).newInstance(output, registries);
                Object value = decode.invoke(vanilla, nativeInput);
                assertEquals(0, byteBuf.getMethod("readableBytes").invoke(input));
                encode.invoke(vanilla, nativeOutput, value);
                byte[] actual =
                        new byte[(int) byteBuf.getMethod("readableBytes").invoke(output)];
                byteBuf.getMethod("readBytes", byte[].class).invoke(output, (Object) actual);
                var left = Unpooled.wrappedBuffer(expected);
                var right = Unpooled.wrappedBuffer(actual);
                try {
                    T before = codec.decode(left), after = codec.decode(right);
                    assertFalse(left.isReadable());
                    assertFalse(right.isReadable());
                    if (!before.equals(after) && json) {
                        var a = Unpooled.wrappedBuffer(body(codec, before));
                        var b = Unpooled.wrappedBuffer(body(codec, after));
                        try {
                            assertEquals(
                                    JsonCodecs.json(262144).decode(a),
                                    JsonCodecs.json(262144).decode(b));
                        } finally {
                            a.release();
                            b.release();
                        }
                    } else assertEquals(before, after);
                } finally {
                    left.release();
                    right.release();
                }
            } finally {
                byteBuf.getMethod("release").invoke(input);
                byteBuf.getMethod("release").invoke(output);
            }
        }
    }

    private static <T> byte[] body(PacketCodec<T> codec, T value) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, value);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }
}
