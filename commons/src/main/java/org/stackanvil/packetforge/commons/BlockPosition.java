package org.stackanvil.packetforge.commons;

/** A position encoded as signed 26-bit X/Z and signed 12-bit Y. */
public record BlockPosition(int x, int y, int z) {
    public BlockPosition {
        if (x < -33554432 || x > 33554431 || z < -33554432 || z > 33554431 || y < -2048 || y > 2047) throw new IllegalArgumentException("Position exceeds packed coordinate range");
    }
    public long packed() { return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | (long) y & 0xfffL; }
    public static BlockPosition unpack(long value) { return new BlockPosition((int) (value >> 38), (int) (value << 52 >> 52), (int) (value << 26 >> 38)); }
}
