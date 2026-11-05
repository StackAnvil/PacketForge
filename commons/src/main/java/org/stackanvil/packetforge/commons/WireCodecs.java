package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.OptionalInt;
import java.time.Instant;
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
    public static final PacketCodec<Unit> UNIT = of(b -> Unit.INSTANCE, (b, value) -> { if (value != Unit.INSTANCE) throw new IllegalArgumentException("Expected unit value"); });
    public static <T> PacketCodec<T> lazy(java.util.function.Supplier<PacketCodec<T>> supplier) {
        return new PacketCodec<>() {
            private volatile PacketCodec<T> codec;
            private PacketCodec<T> resolved() {
                PacketCodec<T> value = codec;
                if (value == null) synchronized (this) { value = codec; if (value == null) codec = value = java.util.Objects.requireNonNull(supplier.get()); }
                return value;
            }
            public T decode(ByteBuf input) { return resolved().decode(input); }
            public void encode(ByteBuf output, T value) { resolved().encode(output, value); }
        };
    }
    public static <K> PacketCodec<Variant<K>> dispatch(PacketCodec<K> key, Map<K, PacketCodec<?>> cases) {
        Map<K, PacketCodec<?>> table = Map.copyOf(cases);
        return of(b -> { K type = key.decode(b); PacketCodec<?> codec = table.get(type); if (codec == null) throw new IllegalArgumentException("Unknown codec discriminator: " + type); return new Variant<>(type, codec.decode(b)); },
            (b, value) -> {
                @SuppressWarnings("unchecked") PacketCodec<Object> codec = (PacketCodec<Object>) table.get(value.type());
                if (codec == null) throw new IllegalArgumentException("Unknown codec discriminator: " + value.type());
                key.encode(b, value.type()); codec.encode(b, value.value());
            });
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
    public static final PacketCodec<ChunkPosition> CHUNK_POSITION = of(b -> ChunkPosition.unpack(b.readLong()), (b, v) -> b.writeLong(v.packed()));
    public static final PacketCodec<Instant> INSTANT = mapped(LONG, Instant::ofEpochMilli, Instant::toEpochMilli);
    public static final PacketCodec<OptionalInt> OPTIONAL_VAR_INT = mapped(VAR_INT, v -> v == 0 ? OptionalInt.empty() : OptionalInt.of(v - 1), v -> v.isPresent() ? v.getAsInt() + 1 : 0);
    public static final PacketCodec<long[]> LONG_ARRAY = of(b -> {
        int count = checkedLength(VAR_INT.decode(b), b.readableBytes() / 8);
        long[] values = new long[count]; for (int i = 0; i < count; i++) values[i] = b.readLong(); return values;
    }, (b, values) -> { VAR_INT.encode(b, values.length); for (long value : values) b.writeLong(value); });
    public static final PacketCodec<int[]> VAR_INT_ARRAY = of(b -> {
        int count = checkedLength(VAR_INT.decode(b), b.readableBytes());
        int[] values = new int[count]; for (int i = 0; i < count; i++) values[i] = VAR_INT.decode(b); return values;
    }, (b, values) -> { VAR_INT.encode(b, values.length); for (int value : values) VAR_INT.encode(b, value); });
    public static final PacketCodec<BitSet> BIT_SET = mapped(byteArray(Integer.MAX_VALUE), BitSet::valueOf, BitSet::toByteArray);
    public static <A, B> PacketCodec<B> mapped(PacketCodec<A> original, Function<A, B> decode, Function<B, A> encode) {
        return of(b -> decode.apply(original.decode(b)), (b, value) -> original.encode(b, encode.apply(value)));
    }
    public static <T> PacketCodec<T> lengthPrefixed(PacketCodec<T> original, int maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative payload limit");
        return of(b -> { int count = checkedLength(VAR_INT.decode(b), Math.min(maxBytes, b.readableBytes())); return original.decode(b.readSlice(count)); },
            (b, value) -> {
                ByteBuf scratch = b.alloc().buffer();
                try { original.encode(scratch, value); checkedLength(scratch.readableBytes(), maxBytes); VAR_INT.encode(b, scratch.readableBytes()); b.writeBytes(scratch); }
                finally { scratch.release(); }
            });
    }
    public static PacketCodec<BitSet> fixedBitSet(int bits) {
        if (bits < 0 || bits > Integer.MAX_VALUE - 7) throw new IllegalArgumentException("Invalid bit count");
        int bytes = (bits + 7) / 8;
        return mapped(fixedBytes(bytes), BitSet::valueOf, value -> {
            if (value.length() > bits) throw new IllegalArgumentException("Bit set exceeds fixed width");
            return java.util.Arrays.copyOf(value.toByteArray(), bytes);
        });
    }
    public static <T> PacketCodec<List<T>> fixedList(PacketCodec<T> element, int size) {
        if (size < 0) throw new IllegalArgumentException("Negative list size");
        return of(b -> { List<T> values = new ArrayList<>(Math.min(size, 65536)); for (int i = 0; i < size; i++) values.add(element.decode(b)); return List.copyOf(values); },
            (b, values) -> { if (values.size() != size) throw new IllegalArgumentException("Expected " + size + " values"); for (T value : values) element.encode(b, value); });
    }
    public static <T> PacketCodec<Set<T>> set(PacketCodec<T> element, int maxSize) {
        return mapped(list(element, maxSize), values -> java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values)), ArrayList::new);
    }
    public static <K, V> PacketCodec<Map<K, V>> map(PacketCodec<K> key, PacketCodec<V> value, int maxSize) {
        return of(b -> {
            int count = checkedLength(VAR_INT.decode(b), maxSize);
            Map<K, V> values = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) values.put(key.decode(b), value.decode(b));
            return java.util.Collections.unmodifiableMap(values);
        }, (b, values) -> { checkedLength(values.size(), maxSize); VAR_INT.encode(b, values.size()); values.forEach((k, v) -> { key.encode(b, k); value.encode(b, v); }); });
    }
    public static <T> PacketCodec<RegistryValue<T>> registryValue(PacketCodec<T> direct) {
        return of(b -> {
            int id = VAR_INT.decode(b);
            if (id < 0) throw new IllegalArgumentException("Negative registry wire ID");
            return id == 0 ? new RegistryValue.Direct<>(direct.decode(b)) : new RegistryValue.Reference<>(id - 1);
        }, (b, value) -> {
            switch (value) {
                case RegistryValue.Reference<T> reference -> VAR_INT.encode(b, reference.id() + 1);
                case RegistryValue.Direct<T> inline -> { VAR_INT.encode(b, 0); direct.encode(b, inline.value()); }
            }
        });
    }
    public static <L, R> PacketCodec<Either<L, R>> either(PacketCodec<L> left, PacketCodec<R> right) {
        return of(b -> b.readBoolean() ? Either.left(left.decode(b)) : Either.right(right.decode(b)), (b, value) -> {
            switch (value) {
                case Either.Left<L, R> branch -> { b.writeBoolean(true); left.encode(b, branch.value()); }
                case Either.Right<L, R> branch -> { b.writeBoolean(false); right.encode(b, branch.value()); }
            }
        });
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
    public static PacketCodec<byte[]> fixedBytes(int size) {
        if (size < 0) throw new IllegalArgumentException("Negative byte array size");
        return of(b -> { checkedLength(size, b.readableBytes()); byte[] value = new byte[size]; b.readBytes(value); return value; }, (b, value) -> { if (value.length != size) throw new IllegalArgumentException("Wrong byte array size"); b.writeBytes(value); });
    }
    public static void readFixedInto(ByteBuf input, byte[] value) { input.readBytes(value); }
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
