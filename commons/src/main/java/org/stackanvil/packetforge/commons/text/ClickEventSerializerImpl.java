/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

final class ClickEventSerializerImpl {

    static ClickEvent<?> deserialize(NbtMap map) {
        ClickEvent.Action<?> action = ClickEvent.Action.NAMES.valueOrThrow(map.getString("action"));

        // switch case will intentionally break compile with new actions
        return switch (action) {
            case ClickEvent.Action.OpenUrl ignored -> ClickEvent.openUrl(map.getString("url"));
            case ClickEvent.Action.OpenFile ignored ->
                throw new IllegalArgumentException("Open-file click events are not allowed in protocol text");
            case ClickEvent.Action.RunCommand ignored -> ClickEvent.runCommand(map.getString("command"));
            case ClickEvent.Action.SuggestCommand ignored -> ClickEvent.suggestCommand(map.getString("command"));
            case ClickEvent.Action.ChangePage ignored -> ClickEvent.changePage(map.getInt("page"));
            case ClickEvent.Action.CopyToClipboard ignored -> ClickEvent.copyToClipboard(map.getString("value"));
            case ClickEvent.Action.ShowDialog ignored -> {
                Object tag = map.get("dialog");
                if (tag instanceof String reference) {
                    yield ClickEvent.showDialog(new NbtDialog(Key.key(reference)));
                } else if (tag instanceof NbtMap inline) {
                    yield ClickEvent.showDialog(new NbtDialog(
                            (org.stackanvil.packetforge.commons.NbtTag.Compound) TextNbt.fromValue(inline)));
                }
                throw new IllegalArgumentException(
                        "Expected \"dialog\" of \"show_dialog\" click event to be a string reference or compound tag, got: "
                                + tag);
            }
            case ClickEvent.Action.Custom ignored ->
                ClickEvent.custom(
                        Key.key(map.getString("id")),
                        map.containsKey("payload")
                                ? BinaryTagHolder.binaryTagHolder(TextNbt.toSnbt(map.get("payload")))
                                : null);
        };
    }

    static NbtMap serialize(ClickEvent<?> event) {
        NbtMapBuilder builder = NbtMap.builder();
        builder.putString("action", event.action().name());
        ClickEvent.Payload payload = event.payload();

        // switch case will intentionally break compile with new actions
        return switch (event.action()) {
            case ClickEvent.Action.OpenUrl ignored ->
                builder.putString("url", asText(payload)).build();
            case ClickEvent.Action.OpenFile ignored ->
                throw new IllegalArgumentException("Open-file click events are not allowed in protocol text");
            case ClickEvent.Action.RunCommand ignored ->
                builder.putString("command", asText(payload)).build();
            case ClickEvent.Action.SuggestCommand ignored ->
                builder.putString("command", asText(payload)).build();
            case ClickEvent.Action.ChangePage ignored ->
                builder.putInt("page", asInt(payload)).build();
            case ClickEvent.Action.CopyToClipboard ignored ->
                builder.putString("value", asText(payload)).build();
            case ClickEvent.Action.ShowDialog ignored -> {
                ClickEvent.Payload.Dialog dialogPayload = (ClickEvent.Payload.Dialog) payload;
                if (dialogPayload.dialog() instanceof NbtDialog(var reference, var inline)) {
                    reference.ifPresent(key -> builder.putString("dialog", key.asString()));
                    inline.ifPresent(map -> builder.putCompound("dialog", (NbtMap) TextNbt.toValue(map)));
                } else if (dialogPayload.dialog() instanceof JsonDialog inline) {
                    builder.putCompound("dialog", (NbtMap) TextNbt.toValue(TextJson.toNbt(inline.value())));
                } else {
                    throw new IllegalArgumentException("Unsupported dialog data implementation: "
                            + dialogPayload.dialog().getClass());
                }
                yield builder.build();
            }
            case ClickEvent.Action.Custom ignored -> {
                ClickEvent.Payload.Custom customPayload = (ClickEvent.Payload.Custom) payload;
                builder.putString("id", customPayload.key().asString());

                if (customPayload.nbt() != null)
                    builder.put("payload", TextNbt.fromSnbt(customPayload.nbt().string()));
                yield builder.build();
            }
        };
    }

    private static String asText(ClickEvent.Payload payload) {
        return ((ClickEvent.Payload.Text) payload).value();
    }

    private static int asInt(ClickEvent.Payload payload) {
        return ((ClickEvent.Payload.Int) payload).integer();
    }
}
