/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

final class StyleSerializerImpl {

    static Style deserialize(NbtMap map, NbtComponentSerializer componentSerializer) {
        for (String field : java.util.List.of("color", "font", "insertion"))
            if (map.containsKey(field) && !(map.get(field) instanceof String))
                throw new IllegalArgumentException("Expected text style string: " + field);
        for (String field : java.util.List.of("click_event", "hover_event"))
            if (map.containsKey(field) && !(map.get(field) instanceof NbtMap))
                throw new IllegalArgumentException("Expected text style event object: " + field);
        return Style.style(builder -> {
            map.listenForString("color", color -> {
                if (color.startsWith("#") && color.length() != 7)
                    throw new IllegalArgumentException("Text colors require six hexadecimal digits");
                TextColor parsed = TextColor.fromHexString(color);
                if (parsed == null) {
                    parsed = NamedTextColor.NAMES.valueOrThrow(color);
                }
                builder.color(parsed);
            });
            Object shadowColor = map.get("shadow_color");
            if (shadowColor != null) {
                builder.shadowColor(NbtUtil.deserializeARGB(shadowColor));
            }

            for (var decoration : TextDecoration.values()) {
                String field = decoration.toString();
                if (map.containsKey(field)) {
                    if (!(map.get(field) instanceof Number value))
                        throw new IllegalArgumentException("Expected numeric NBT style flag: " + field);
                    builder.decoration(decoration, value.byteValue() != 0);
                }
            }

            map.listenForCompound(
                    "click_event", clickEvent -> builder.clickEvent(ClickEventSerializerImpl.deserialize(clickEvent)));
            map.listenForCompound(
                    "hover_event",
                    hoverEvent ->
                            builder.hoverEvent(HoverEventSerializerImpl.deserialize(hoverEvent, componentSerializer)));

            map.listenForString("insertion", builder::insertion);
            map.listenForString("font", font -> builder.font(Key.key(font)));
        });
    }

    static void serialize(NbtMapBuilder builder, Style style, NbtComponentSerializer componentSerializer) {
        NbtUtil.checkNonNull(style.color(), color -> {
            if (color instanceof NamedTextColor named) {
                builder.putString("color", named.name());
            } else {
                builder.putString("color", color.asHexString());
            }
        });
        NbtUtil.checkNonNull(style.shadowColor(), color -> builder.putInt("shadow_color", color.value()));

        style.decorations().forEach((decoration, state) -> {
            if (state != TextDecoration.State.NOT_SET) {
                builder.putBoolean(decoration.toString(), state == TextDecoration.State.TRUE);
            }
        });

        NbtUtil.checkNonNull(
                style.clickEvent(),
                clickEvent -> builder.putCompound("click_event", ClickEventSerializerImpl.serialize(clickEvent)));
        NbtUtil.checkNonNull(
                style.hoverEvent(),
                hoverEvent -> builder.putCompound(
                        "hover_event", HoverEventSerializerImpl.serialize(hoverEvent, componentSerializer)));

        NbtUtil.checkNonNull(style.insertion(), insertion -> builder.putString("insertion", insertion));
        NbtUtil.checkNonNull(style.font(), font -> builder.putString("font", font.asString()));
    }
}
