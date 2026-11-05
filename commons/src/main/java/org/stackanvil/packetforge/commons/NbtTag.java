package org.stackanvil.packetforge.commons;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The typed tree carried by network NBT. Root names are absent on the network. */
public sealed interface NbtTag {
    int type();

    enum End implements NbtTag {
        INSTANCE;

        public int type() {
            return 0;
        }
    }

    record ByteValue(byte value) implements NbtTag {
        public int type() {
            return 1;
        }
    }

    record ShortValue(short value) implements NbtTag {
        public int type() {
            return 2;
        }
    }

    record IntValue(int value) implements NbtTag {
        public int type() {
            return 3;
        }
    }

    record LongValue(long value) implements NbtTag {
        public int type() {
            return 4;
        }
    }

    record FloatValue(float value) implements NbtTag {
        public int type() {
            return 5;
        }
    }

    record DoubleValue(double value) implements NbtTag {
        public int type() {
            return 6;
        }
    }

    record ByteArray(byte[] value) implements NbtTag {
        public ByteArray {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArray that && java.util.Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }

        public int type() {
            return 7;
        }
    }

    record StringValue(String value) implements NbtTag {
        public StringValue {
            Objects.requireNonNull(value);
        }

        public int type() {
            return 8;
        }
    }

    record ListValue(int elementType, List<NbtTag> values) implements NbtTag {
        public ListValue {
            values = List.copyOf(values);
            if (elementType < 0 || elementType > 12 || elementType == 0 && !values.isEmpty())
                throw new IllegalArgumentException("Invalid NBT list type");
            for (NbtTag tag : values)
                if (tag.type() != elementType)
                    throw new IllegalArgumentException("NBT list contains mixed wire types");
        }

        public int type() {
            return 9;
        }
    }

    record Compound(Map<String, NbtTag> values) implements NbtTag {
        public Compound {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            values.forEach(
                    (key, value) -> {
                        Objects.requireNonNull(key);
                        Objects.requireNonNull(value);
                        if (value.type() == 0)
                            throw new IllegalArgumentException("Compound contains an end tag");
                    });
        }

        public int type() {
            return 10;
        }
    }

    record IntArray(int[] value) implements NbtTag {
        public IntArray {
            value = value.clone();
        }

        @Override
        public int[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntArray that && java.util.Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }

        public int type() {
            return 11;
        }
    }

    record LongArray(long[] value) implements NbtTag {
        public LongArray {
            value = value.clone();
        }

        @Override
        public long[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LongArray that && java.util.Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }

        public int type() {
            return 12;
        }
    }
}
