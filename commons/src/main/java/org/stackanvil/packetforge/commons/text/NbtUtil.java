/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.util.ARGBLike;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.jspecify.annotations.Nullable;

final class NbtUtil {

    static net.kyori.adventure.text.BlockNBTComponent.Pos deserializeBlockPos(String value) {
        String[] coordinates = value.trim().split("\\s+");
        if (coordinates.length != 3) throw new IllegalArgumentException("Expected three block NBT coordinates");
        for (int i = 0; i < coordinates.length; i++)
            if (coordinates[i].equals("~") || coordinates[i].equals("^")) coordinates[i] += "0";
        return net.kyori.adventure.text.BlockNBTComponent.Pos.fromString(String.join(" ", coordinates));
    }

    static UUID deserializeLenientUUID(Object object) {
        if (object instanceof int[] array) {
            return deserializeUUID(array);
        } else if (object instanceof String string) {
            return UUID.fromString(string);
        }
        throw new IllegalArgumentException("Don't know how to leniently parse UUID: " + object);
    }

    static UUID deserializeUUID(int[] array) {
        if (array.length != 4) {
            throw new IllegalArgumentException(
                    "UUID int array must have exactly 4 ints, got: " + Arrays.toString(array));
        }
        return new UUID(
                (long) array[0] << 32 | (array[1] & 0xFFFFFFFFL), (long) array[2] << 32 | (array[3] & 0xFFFFFFFFL));
    }

    static int[] serializeUUID(UUID uuid) {
        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();
        return new int[] {
            (int) (mostSignificantBits >> 32),
            (int) mostSignificantBits,
            (int) (leastSignificantBits >> 32),
            (int) leastSignificantBits
        };
    }

    static ARGBLike deserializeARGB(Object object) {
        if (object instanceof NbtList<?> list) {
            if (list.size() != 4 || !list.stream().allMatch(Number.class::isInstance)) {
                throw new IllegalArgumentException("ARGB vector must contain exactly 4 floats, got: " + list);
            }
            @SuppressWarnings("unchecked")
            NbtList<? extends Number> floats = (NbtList<? extends Number>) list;
            return ShadowColor.shadowColor(
                    ratioFloatToByte(floats.get(0).floatValue()),
                    ratioFloatToByte(floats.get(1).floatValue()),
                    ratioFloatToByte(floats.get(2).floatValue()),
                    ratioFloatToByte(floats.get(3).floatValue()));
        } else if (object instanceof Number number) {
            return ShadowColor.shadowColor(number.intValue());
        }
        throw new IllegalArgumentException("Don't know how to parse ARGB colour: " + object);
    }

    private static int ratioFloatToByte(float f) {
        return (int) Math.floor(f * 255.0F);
    }

    static <T> void checkNonNull(@Nullable T value, Consumer<T> consumer) {
        if (value != null) {
            consumer.accept(value);
        }
    }

    static void putIfFalse(NbtMapBuilder builder, String key, boolean value) {
        if (!value) {
            builder.putBoolean(key, false);
        }
    }

    static void putIfTrue(NbtMapBuilder builder, String key, boolean value) {
        if (value) {
            builder.putBoolean(key, true);
        }
    }
}
