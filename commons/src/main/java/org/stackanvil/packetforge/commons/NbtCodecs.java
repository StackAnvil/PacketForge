package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Big-endian network NBT with bounded depth and allocation. */
public final class NbtCodecs {
    private NbtCodecs() {}

    public static final PacketCodec<NbtTag> TAG = tag(2_097_152, 512, false);
    public static final PacketCodec<NbtTag> TRUSTED_TAG = tag(Integer.MAX_VALUE, 512, false);
    public static final PacketCodec<NbtTag.Compound> COMPOUND = compound(2_097_152, 512, false);
    public static final PacketCodec<NbtTag.Compound> NULLABLE_COMPOUND =
            compound(2_097_152, 512, true);
    public static final PacketCodec<Optional<NbtTag.Compound>> OPTIONAL_COMPOUND =
            WireCodecs.mapped(NULLABLE_COMPOUND, Optional::ofNullable, v -> v.orElse(null));

    public static PacketCodec<Optional<NbtTag>> optionalTag(int maxBytes, int maxDepth) {
        return WireCodecs.mapped(
                tag(maxBytes, maxDepth, true),
                v -> v.type() == 0 ? Optional.empty() : Optional.of(v),
                v -> v.orElse(NbtTag.End.INSTANCE));
    }

    public static PacketCodec<NbtTag.Compound> compound(
            int maxBytes, int maxDepth, boolean nullable) {
        PacketCodec<NbtTag> codec = tag(maxBytes, maxDepth, nullable);
        return WireCodecs.mapped(
                codec,
                v -> {
                    if (nullable && v.type() == 0) return null;
                    if (!(v instanceof NbtTag.Compound compound))
                        throw new IllegalArgumentException("Expected compound NBT");
                    return compound;
                },
                v -> v == null && nullable ? NbtTag.End.INSTANCE : v);
    }

    public static PacketCodec<NbtTag> tag(int maxBytes, int maxDepth, boolean endAllowed) {
        if (maxBytes < 1 || maxDepth < 0) throw new IllegalArgumentException("Invalid NBT limits");
        return WireCodecs.of(
                b -> {
                    int type = b.readUnsignedByte();
                    if (type == 0 && !endAllowed)
                        throw new IllegalArgumentException("Unexpected end tag");
                    return read(b, type, new Budget(maxBytes, maxDepth), 0);
                },
                (b, value) -> {
                    if (value == null || value.type() == 0 && !endAllowed)
                        throw new IllegalArgumentException("Unexpected end tag");
                    b.writeByte(value.type());
                    write(b, value, new Budget(maxBytes, maxDepth), 0);
                });
    }

    private static final class Budget {
        long remaining;
        final int depth;

        Budget(long size, int depth) {
            remaining = size;
            this.depth = depth;
        }

        void take(long count, int currentDepth) {
            if (count < 0 || currentDepth > depth || count > remaining)
                throw new IllegalArgumentException("NBT exceeds its byte or depth limit");
            remaining -= count;
        }
    }

    private static int fixedWidth(int type) {
        return switch (type) {
            case 1 -> 1;
            case 2 -> 2;
            case 3, 5 -> 4;
            case 4, 6 -> 8;
            default -> 0;
        };
    }

    private static NbtTag read(ByteBuf b, int type, Budget budget, int depth) {
        budget.take(1L + fixedWidth(type), depth);
        return switch (type) {
            case 0 -> NbtTag.End.INSTANCE;
            case 1 -> new NbtTag.ByteValue(b.readByte());
            case 2 -> new NbtTag.ShortValue(b.readShort());
            case 3 -> new NbtTag.IntValue(b.readInt());
            case 4 -> new NbtTag.LongValue(b.readLong());
            case 5 -> new NbtTag.FloatValue(b.readFloat());
            case 6 -> new NbtTag.DoubleValue(b.readDouble());
            case 7 -> {
                int count = count(b, 1, budget, depth);
                byte[] value = new byte[count];
                b.readBytes(value);
                yield new NbtTag.ByteArray(value);
            }
            case 8 -> new NbtTag.StringValue(readUtf(b, budget, depth));
            case 9 -> {
                int element = b.readUnsignedByte(), count = count(b, 1, budget, depth);
                if (element < 0 || element > 12 || element == 0 && count != 0)
                    throw new IllegalArgumentException("Invalid NBT list type");
                var values = new ArrayList<NbtTag>(Math.min(count, 65536));
                for (int i = 0; i < count; i++) values.add(read(b, element, budget, depth + 1));
                yield new NbtTag.ListValue(element, values);
            }
            case 10 -> {
                var values = new LinkedHashMap<String, NbtTag>();
                int next;
                while ((next = b.readUnsignedByte()) != 0) {
                    String name = readUtf(b, budget, depth);
                    values.put(name, read(b, next, budget, depth + 1));
                }
                yield new NbtTag.Compound(values);
            }
            case 11 -> {
                int count = count(b, 4, budget, depth);
                int[] value = new int[count];
                for (int i = 0; i < count; i++) value[i] = b.readInt();
                yield new NbtTag.IntArray(value);
            }
            case 12 -> {
                int count = count(b, 8, budget, depth);
                long[] value = new long[count];
                for (int i = 0; i < count; i++) value[i] = b.readLong();
                yield new NbtTag.LongArray(value);
            }
            default -> throw new IllegalArgumentException("Unknown NBT tag type: " + type);
        };
    }

    private static int count(ByteBuf b, int width, Budget budget, int depth) {
        int value = b.readInt();
        if (value < 0 || value > b.readableBytes() / width)
            throw new IllegalArgumentException("Invalid NBT array or list length");
        budget.take((long) value * width, depth);
        return value;
    }

    private static String readUtf(ByteBuf b, Budget budget, int depth) {
        budget.take((long) b.getUnsignedShort(b.readerIndex()) + 2, depth);
        try {
            return new DataInputStream(new ByteBufInputStream(b)).readUTF();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid NBT modified UTF-8", e);
        }
    }

    private static void writeUtf(ByteBuf b, String value, Budget budget, int depth) {
        long size = 2;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            size += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
        }
        budget.take(size, depth);
        try {
            new DataOutputStream(new ByteBufOutputStream(b)).writeUTF(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid NBT modified UTF-8", e);
        }
    }

    private static void write(ByteBuf b, NbtTag tag, Budget budget, int depth) {
        budget.take(1L + fixedWidth(tag.type()), depth);
        switch (tag) {
            case NbtTag.End ignored -> {}
            case NbtTag.ByteValue value -> b.writeByte(value.value());
            case NbtTag.ShortValue value -> b.writeShort(value.value());
            case NbtTag.IntValue value -> b.writeInt(value.value());
            case NbtTag.LongValue value -> b.writeLong(value.value());
            case NbtTag.FloatValue value -> b.writeFloat(value.value());
            case NbtTag.DoubleValue value -> b.writeDouble(value.value());
            case NbtTag.ByteArray value -> {
                byte[] data = value.value();
                budget.take(data.length, depth);
                b.writeInt(data.length);
                b.writeBytes(data);
            }
            case NbtTag.StringValue value -> writeUtf(b, value.value(), budget, depth);
            case NbtTag.ListValue value -> {
                budget.take(value.values().size(), depth);
                b.writeByte(value.elementType());
                b.writeInt(value.values().size());
                for (var entry : value.values()) write(b, entry, budget, depth + 1);
            }
            case NbtTag.Compound value -> {
                value.values()
                        .forEach(
                                (name, entry) -> {
                                    b.writeByte(entry.type());
                                    writeUtf(b, name, budget, depth);
                                    write(b, entry, budget, depth + 1);
                                });
                b.writeByte(0);
            }
            case NbtTag.IntArray value -> {
                int[] data = value.value();
                budget.take((long) data.length * 4, depth);
                b.writeInt(data.length);
                for (int item : data) b.writeInt(item);
            }
            case NbtTag.LongArray value -> {
                long[] data = value.value();
                budget.take((long) data.length * 8, depth);
                b.writeInt(data.length);
                for (long item : data) b.writeLong(item);
            }
        }
    }
}
