/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

final class ResolvableProfileSerializerImpl {

    static PlayerHeadObjectContents.Builder deserialize(Object object) {
        return switch (object) {
            case String name -> ObjectContents.playerHead(name).toBuilder();
            case NbtMap map -> {
                PlayerHeadObjectContents.Builder builder = ObjectContents.playerHead();
                map.listenForString("name", builder::name);
                map.listenForIntArray("id", array -> builder.id(NbtUtil.deserializeUUID(array)));
                map.listenForString("texture", texture -> builder.texture(Key.key(texture)));

                map.listenForList(
                        "properties",
                        NbtType.COMPOUND,
                        properties -> properties.stream()
                                .map(ResolvableProfileSerializerImpl::deserializeProfileProperty)
                                .forEach(builder::profileProperty));
                yield builder;
            }
            default -> throw new IllegalArgumentException("Don't know how to parse resolvable profile: " + object);
        };
    }

    static NbtMap serialize(PlayerHeadObjectContents profile) {
        NbtMapBuilder builder = NbtMap.builder();
        NbtUtil.checkNonNull(profile.name(), name -> builder.putString("name", name));
        NbtUtil.checkNonNull(profile.id(), uuid -> builder.putIntArray("id", NbtUtil.serializeUUID(uuid)));
        NbtUtil.checkNonNull(profile.texture(), texture -> builder.putString("texture", texture.asString()));

        List<NbtMap> properties = profile.profileProperties().stream()
                .map(ResolvableProfileSerializerImpl::serializeProfileProperty)
                .toList();
        if (!properties.isEmpty()) {
            builder.putList("properties", NbtType.COMPOUND, properties);
        }

        return builder.build();
    }

    private static PlayerHeadObjectContents.ProfileProperty deserializeProfileProperty(NbtMap map) {
        String name = map.getString("name");
        String value = map.getString("value");
        String signature = map.getString("signature", null);
        return PlayerHeadObjectContents.property(name, value, signature);
    }

    private static NbtMap serializeProfileProperty(PlayerHeadObjectContents.ProfileProperty property) {
        NbtMapBuilder builder = NbtMap.builder();
        builder.putString("name", property.name());
        builder.putString("value", property.value());
        NbtUtil.checkNonNull(property.signature(), signature -> builder.putString("signature", signature));
        return builder.build();
    }
}
