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
import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.util.ARGBLike;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.jspecify.annotations.Nullable;

final class NbtUtil {

    static BlockNBTComponent.Pos deserializeBlockPos(String value) {
        String[] coordinates = value.split(" ", -1);
        if (coordinates.length != 3 || coordinates[2].isEmpty())
            throw new IllegalArgumentException("Expected three block NBT coordinates");
        if (value.startsWith("^")) {
            double[] local = new double[3];
            for (int i = 0; i < 3; i++) {
                if (!coordinates[i].startsWith("^")) throw new IllegalArgumentException("Mixed block coordinate types");
                local[i] = coordinateDouble(coordinates[i].substring(1));
            }
            var parsed = BlockNBTComponent.LocalPos.localPos(local[0], local[1], local[2]);
            return parsed.asString().equals(value) ? parsed : new SourceLocalPos(value, parsed);
        }
        var world = new BlockNBTComponent.WorldPos.Coordinate[3];
        boolean integral = true;
        for (int i = 0; i < 3; i++) {
            String coordinate = coordinates[i];
            boolean relative = coordinate.startsWith("~");
            double number;
            if (relative) number = coordinateDouble(coordinate.substring(1));
            else if (coordinate.isEmpty()) number = 0;
            else {
                if (!coordinate.matches("-?[0-9]+"))
                    throw new IllegalArgumentException("Invalid absolute block coordinate");
                number = Integer.parseInt(coordinate);
            }
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE || number != (int) number) integral = false;
            else
                world[i] = relative
                        ? BlockNBTComponent.WorldPos.Coordinate.relative((int) number)
                        : BlockNBTComponent.WorldPos.Coordinate.absolute((int) number);
        }
        if (!integral) return new SourcePos(value);
        var parsed = BlockNBTComponent.WorldPos.worldPos(world[0], world[1], world[2]);
        return parsed.asString().equals(value) ? parsed : new SourceWorldPos(value, parsed);
    }

    private static double coordinateDouble(String number) {
        if (number.isEmpty()) return 0;
        if (!number.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))
            throw new IllegalArgumentException("Invalid relative block coordinate");
        return Double.parseDouble(number);
    }

    private record SourcePos(String asString) implements BlockNBTComponent.Pos {}

    private record SourceWorldPos(String source, BlockNBTComponent.WorldPos parsed)
            implements BlockNBTComponent.WorldPos {
        public String asString() {
            return source;
        }

        public Coordinate x() {
            return parsed.x();
        }

        public Coordinate y() {
            return parsed.y();
        }

        public Coordinate z() {
            return parsed.z();
        }
    }

    private record SourceLocalPos(String source, BlockNBTComponent.LocalPos parsed)
            implements BlockNBTComponent.LocalPos {
        public String asString() {
            return source;
        }

        public double left() {
            return parsed.left();
        }

        public double up() {
            return parsed.up();
        }

        public double forwards() {
            return parsed.forwards();
        }
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
