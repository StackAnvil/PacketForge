package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Shared wire operations used by generated codecs. All lengths are checked before allocation. */
public final class WireCodecs {
    private WireCodecs() {}
    public static <T> PacketCodec<T> of(Function<ByteBuf, T> read, BiConsumer<ByteBuf, T> write) {
        return new PacketCodec<>() {
            public T decode(ByteBuf input) { return read.apply(input); }
            public void encode(ByteBuf output, T value) { write.accept(output, value); }
        };
    }
    public static final PacketCodec<Boolean> BOOL = of(ByteBuf::readBoolean, ByteBuf::writeBoolean);
    public static final PacketCodec<Byte> BYTE = of(ByteBuf::readByte, (b, v) -> b.writeByte(v));
    public static final PacketCodec<Integer> UNSIGNED_BYTE = of(b -> (int) b.readUnsignedByte(), ByteBuf::writeByte);
    public static final PacketCodec<Short> SHORT = of(ByteBuf::readShort, (b, v) -> b.writeShort(v));
    public static final PacketCodec<Integer> UNSIGNED_SHORT = of(ByteBuf::readUnsignedShort, ByteBuf::writeShort);
    public static final PacketCodec<Integer> INT = of(ByteBuf::readInt, ByteBuf::writeInt);
    public static final PacketCodec<Long> LONG = of(ByteBuf::readLong, ByteBuf::writeLong);
    public static final PacketCodec<Float> FLOAT = of(ByteBuf::readFloat, ByteBuf::writeFloat);
    public static final PacketCodec<Double> DOUBLE = of(ByteBuf::readDouble, ByteBuf::writeDouble);
    public static final PacketCodec<Integer> VAR_INT = of(b -> (int) readVar(b, 5), (b, v) -> writeVar(b, Integer.toUnsignedLong(v)));
    public static final PacketCodec<Long> VAR_LONG = of(b -> readVar(b, 10), WireCodecs::writeVar);
    public static final PacketCodec<UUID> UUID_CODEC = of(b -> new UUID(b.readLong(), b.readLong()), (b, v) -> { b.writeLong(v.getMostSignificantBits()); b.writeLong(v.getLeastSignificantBits()); });
    public static final PacketCodec<String> STRING = string(32767);
    public static final PacketCodec<String> IDENTIFIER = of(b -> identifier(STRING.decode(b)), (b, v) -> STRING.encode(b, identifier(v)));
    public static final PacketCodec<BlockPosition> BLOCK_POSITION = of(b -> BlockPosition.unpack(b.readLong()), (b, v) -> b.writeLong(v.packed()));
    private static String identifier(String value) {
        int separator = value.indexOf(':');
        String namespace = separator <= 0 ? "minecraft" : value.substring(0, separator);
        String path = separator < 0 ? value : value.substring(separator + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]*")) throw new IllegalArgumentException("Invalid identifier: " + value);
        return namespace + ":" + path;
    }
    private static long readVar(ByteBuf b, int limit) {
        long value = 0;
        for (int i = 0; i < limit; i++) {
            byte part = b.readByte();
            value |= (long) (part & 127) << (i * 7);
            if ((part & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Variable integer exceeds " + limit + " bytes");
    }
    private static void writeVar(ByteBuf b, long value) {
        while ((value & ~127L) != 0) { b.writeByte((int) value & 127 | 128); value >>>= 7; }
        b.writeByte((int) value);
    }
    public static PacketCodec<String> string(int maxChars) {
        if (maxChars < 0 || maxChars > Integer.MAX_VALUE / 3) throw new IllegalArgumentException("Invalid string limit");
        return of(b -> {
            int size = checkedLength(VAR_INT.decode(b), Math.min(maxChars * 3, b.readableBytes()));
            byte[] data = new byte[size]; b.readBytes(data);
            try {
                String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                if (value.length() > maxChars) throw new IllegalArgumentException("String exceeds character limit");
                return value;
            } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8", e); }
        }, (b, value) -> {
            if (value.length() > maxChars) throw new IllegalArgumentException("String exceeds character limit");
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            if (data.length > maxChars * 3) throw new IllegalArgumentException("String exceeds byte limit");
            VAR_INT.encode(b, data.length); b.writeBytes(data);
        });
    }
    public static PacketCodec<byte[]> byteArray(int maxLength) {
        if (maxLength < 0) throw new IllegalArgumentException("Negative array limit");
        return of(b -> { int size = checkedLength(VAR_INT.decode(b), Math.min(maxLength, b.readableBytes())); byte[] data = new byte[size]; b.readBytes(data); return data; },
            (b, v) -> { checkedLength(v.length, maxLength); VAR_INT.encode(b, v.length); b.writeBytes(v); });
    }
    public static <T> PacketCodec<Optional<T>> optional(PacketCodec<T> codec) {
        return of(b -> b.readBoolean() ? Optional.of(codec.decode(b)) : Optional.empty(), (b, v) -> { b.writeBoolean(v.isPresent()); v.ifPresent(x -> codec.encode(b, x)); });
    }
    public static <T> PacketCodec<T> nullable(PacketCodec<T> codec) {
        return of(b -> b.readBoolean() ? codec.decode(b) : null, (b, v) -> { b.writeBoolean(v != null); if (v != null) codec.encode(b, v); });
    }
    public static <T> PacketCodec<List<T>> list(PacketCodec<T> codec, int maxLength) {
        if (maxLength < 0) throw new IllegalArgumentException("Negative list limit");
        return of(b -> { int size = checkedLength(VAR_INT.decode(b), maxLength); List<T> values = new ArrayList<>(Math.min(size, 65536)); for (int i = 0; i < size; i++) values.add(codec.decode(b)); return List.copyOf(values); },
            (b, v) -> { checkedLength(v.size(), maxLength); VAR_INT.encode(b, v.size()); v.forEach(x -> codec.encode(b, x)); });
    }
    public static <E extends Enum<E>> PacketCodec<E> enumOrdinal(E[] values) {
        E[] constants = values.clone();
        return of(b -> { int id = checkedLength(VAR_INT.decode(b), constants.length - 1); return constants[id]; }, (b, v) -> VAR_INT.encode(b, v.ordinal()));
    }
    public enum EnumFallback { STRICT, ZERO, WRAP, CLAMP }
    public static <E extends Enum<E>> PacketCodec<E> enumIds(E[] values, int[] ids, PacketCodec<Integer> wire) {
        return enumIds(values, ids, wire, EnumFallback.STRICT);
    }
    public static <E extends Enum<E>> PacketCodec<E> enumIds(E[] values, int[] ids, PacketCodec<Integer> wire, EnumFallback fallback) {
        if (values.length != ids.length) throw new IllegalArgumentException("Enum ID count differs");
        E[] constants = values.clone(); int[] encoded = ids.clone();
        return of(b -> {
            int id = wire.decode(b);
            for (int i = 0; i < encoded.length; i++) if (encoded[i] == id) return constants[i];
            int normalized = switch (fallback) {
                case STRICT -> throw new IllegalArgumentException("Unknown enum ID: " + id);
                case ZERO -> 0;
                case WRAP -> Math.floorMod(id, encoded.length);
                case CLAMP -> Math.clamp(id, 0, encoded.length - 1);
            };
            for (int i = 0; i < encoded.length; i++) if (encoded[i] == normalized) return constants[i];
            throw new IllegalArgumentException("Enum fallback requires continuous IDs");
        }, (b, v) -> wire.encode(b, encoded[v.ordinal()]));
    }
    private static int checkedLength(int size, int max) {
        if (size < 0 || size > max) throw new IllegalArgumentException("Invalid length: " + size + " (limit " + max + ")");
        return size;
    }
}
