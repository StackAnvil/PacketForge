package org.stackanvil.packetforge.commons;

public record ChunkPosition(int x, int z) {
    public long packed() { return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32; }
    public static ChunkPosition unpack(long value) { return new ChunkPosition((int) value, (int) (value >>> 32)); }
}
