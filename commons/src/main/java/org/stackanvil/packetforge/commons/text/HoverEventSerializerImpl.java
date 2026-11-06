/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.jetbrains.annotations.Nullable;

final class HoverEventSerializerImpl {
    static HoverEvent<?> deserialize(NbtMap map, NbtComponentSerializer componentSerializer) {
        HoverEvent.Action<?> action = HoverEvent.Action.NAMES.valueOrThrow(map.getString("action"));

        if (action == HoverEvent.Action.SHOW_TEXT) {
            return HoverEvent.showText(componentSerializer.deserialize(map.get("value")));
        } else if (action == HoverEvent.Action.SHOW_ITEM) {
            Key id = Key.key(map.getString("id"));
            int count = map.getInt("count", 1);
            NbtMap components = map.getCompound("components", null);
            return HoverEvent.showItem(id, count, deserializeDataComponents(components));
        } else if (action == HoverEvent.Action.SHOW_ENTITY) {
            Key id = Key.key(map.getString("id"));
            UUID uuid = NbtUtil.deserializeLenientUUID(map.get("uuid"));
            Object name = map.get("name");
            return HoverEvent.showEntity(id, uuid, componentSerializer.deserializeOrNull(name));
        } else {
            throw new IllegalArgumentException("Don't know how to parse hover event action: " + action.name());
        }
    }

    static NbtMap serialize(HoverEvent<?> event, NbtComponentSerializer componentSerializer) {
        NbtMapBuilder builder = NbtMap.builder();
        builder.putString("action", event.action().name());

        HoverEvent.Action<?> action = event.action();
        Object value = event.value();

        if (action == HoverEvent.Action.SHOW_TEXT) {
            builder.put("value", componentSerializer.serialize((Component) value));
        } else if (action == HoverEvent.Action.SHOW_ITEM) {
            HoverEvent.ShowItem item = (HoverEvent.ShowItem) value;
            builder.put("id", item.item().asString());
            if (item.count() != 1) {
                builder.put("count", item.count());
            }
            if (!item.dataComponents().isEmpty()) {
                builder.put("components", serializeDataComponents(item.dataComponents()));
            }
        } else if (action == HoverEvent.Action.SHOW_ENTITY) {
            HoverEvent.ShowEntity entity = (HoverEvent.ShowEntity) value;
            builder.putString("id", entity.type().asString());
            builder.putIntArray("uuid", NbtUtil.serializeUUID(entity.id()));

            if (entity.name() != null) {
                builder.put("name", componentSerializer.serialize(entity.name()));
            }
        } else {
            throw new IllegalArgumentException("Don't know how to encode hover event action: " + action.name());
        }

        return builder.build();
    }

    private static Map<Key, ? extends DataComponentValue> deserializeDataComponents(@Nullable NbtMap components) {
        if (components == null) {
            return Map.of();
        }

        Map<Key, DataComponentValue> deserialized = new HashMap<>();
        components.forEach((string, tag) -> {
            // '!' indicates removed component
            if (string.startsWith("!")) {
                deserialized.put(Key.key(string.substring(1)), DataComponentValue.removed());
            } else {
                deserialized.put(Key.key(string), new NbtDataComponentValue(TextNbt.fromValue(tag)));
            }
        });
        return Collections.unmodifiableMap(deserialized);
    }

    static NbtMap serializeDataComponents(Map<Key, ? extends DataComponentValue> components) {
        if (components.isEmpty()) {
            return NbtMap.EMPTY;
        }

        NbtMapBuilder serialized = NbtMap.builder();
        components.forEach((key, component) -> {
            if (component instanceof DataComponentValue.Removed) {
                // Removed components are prefixed with a '!', and always represented as an empty map
                serialized.putCompound("!" + key.asString(), NbtMap.EMPTY);
            } else if (component instanceof NbtDataComponentValue(var tag)) {
                serialized.put(key.asString(), TextNbt.toValue(tag));
            } else if (component instanceof net.kyori.adventure.text.serializer.gson.GsonDataComponentValue json) {
                serialized.put(key.asString(), TextNbt.toValue(TextJson.toNbt(json.element())));
            } else if (component instanceof DataComponentValue.TagSerializable snbt) {
                serialized.put(
                        key.asString(), TextNbt.fromSnbt(snbt.asBinaryTag().string()));
            } else {
                throw new IllegalArgumentException(
                        "Don't know how to serialise component of type: " + component.getClass());
            }
        });
        return serialized.build();
    }
}
