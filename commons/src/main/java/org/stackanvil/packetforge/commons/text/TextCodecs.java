package org.stackanvil.packetforge.commons.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import org.cloudburstmc.nbt.NbtMap;
import org.stackanvil.packetforge.commons.NbtCodecs;
import org.stackanvil.packetforge.commons.NbtTag;
import org.stackanvil.packetforge.commons.PacketCodec;
import org.stackanvil.packetforge.commons.WireCodecs;

/** Adventure text values encoded with the source protocol's NBT and JSON layouts. */
public final class TextCodecs {
    private TextCodecs() {}

    public static final PacketCodec<Component> COMPONENT = component(NbtCodecs.TAG);
    public static final PacketCodec<Component> TRUSTED_COMPONENT = component(NbtCodecs.TRUSTED_TAG);
    public static final PacketCodec<Style> STYLE = style(NbtCodecs.TAG);
    public static final PacketCodec<Style> TRUSTED_STYLE = style(NbtCodecs.TRUSTED_TAG);

    private static PacketCodec<Style> style(PacketCodec<NbtTag> nbt) {
        return WireCodecs.mapped(
                nbt,
                value -> {
                    if (!(TextNbt.toValue(value) instanceof NbtMap map))
                        throw new IllegalArgumentException("Expected style compound NBT");
                    return StyleSerializerImpl.deserialize(map, NbtComponentSerializer.nbt());
                },
                style -> {
                    var map = NbtMap.builder();
                    StyleSerializerImpl.serialize(map, style, NbtComponentSerializer.nbt());
                    return TextNbt.fromValue(map.build());
                });
    }

    private static PacketCodec<Component> component(PacketCodec<NbtTag> nbt) {
        return WireCodecs.mapped(
                nbt,
                tag -> NbtComponentSerializer.nbt().deserialize(TextNbt.toValue(tag)),
                value -> TextNbt.fromValue(NbtComponentSerializer.nbt().serialize(value)));
    }

    public static PacketCodec<Component> json(PacketCodec<com.google.gson.JsonElement> json) {
        return WireCodecs.mapped(json, JsonTextSerializer::read, JsonTextSerializer::write);
    }

    public static PacketCodec<Style> jsonStyle(PacketCodec<com.google.gson.JsonElement> json) {
        return WireCodecs.mapped(json, JsonTextSerializer::readStyle, JsonTextSerializer::writeStyle);
    }
}
