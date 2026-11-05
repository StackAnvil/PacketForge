package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;

/** Vector wire primitives, including the scaled 15-bit component encoding. */
public final class VectorCodecs {
    private VectorCodecs() {}

    public static final PacketCodec<IntVector3> INT =
            WireCodecs.of(
                    b -> new IntVector3(b.readInt(), b.readInt(), b.readInt()),
                    (b, v) -> {
                        b.writeInt(v.x());
                        b.writeInt(v.y());
                        b.writeInt(v.z());
                    });
    public static final PacketCodec<Vector3> DOUBLE =
            WireCodecs.of(
                    b -> new Vector3(b.readDouble(), b.readDouble(), b.readDouble()),
                    (b, v) -> {
                        b.writeDouble(v.x());
                        b.writeDouble(v.y());
                        b.writeDouble(v.z());
                    });
    public static final PacketCodec<FloatVector3> FLOAT =
            WireCodecs.of(
                    b -> new FloatVector3(b.readFloat(), b.readFloat(), b.readFloat()),
                    (b, v) -> {
                        b.writeFloat(v.x());
                        b.writeFloat(v.y());
                        b.writeFloat(v.z());
                    });
    public static final PacketCodec<Quaternion> QUATERNION =
            WireCodecs.of(
                    b -> new Quaternion(b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat()),
                    (b, v) -> {
                        b.writeFloat(v.x());
                        b.writeFloat(v.y());
                        b.writeFloat(v.z());
                        b.writeFloat(v.w());
                    });
    public static final PacketCodec<Float> ROTATION_BYTE =
            WireCodecs.mapped(
                    WireCodecs.BYTE,
                    v -> v * 360 / 256.0f,
                    v -> (byte) Math.floor(v * 256.0f / 360.0f));
    public static final PacketCodec<Vector3> LOW_PRECISION =
            WireCodecs.of(VectorCodecs::readScaled, VectorCodecs::writeScaled);

    private static Vector3 readScaled(ByteBuf input) {
        int low = input.readUnsignedByte();
        if (low == 0) return new Vector3(0, 0, 0);
        int middle = input.readUnsignedByte();
        long high = input.readUnsignedInt();
        long bits = high << 16 | middle << 8 | low;
        long scale = low & 3;
        if ((low & 4) != 0) scale |= Integer.toUnsignedLong(WireCodecs.VAR_INT.decode(input)) << 2;
        return new Vector3(
                unpack(bits >> 3) * scale, unpack(bits >> 18) * scale, unpack(bits >> 33) * scale);
    }

    private static void writeScaled(ByteBuf output, Vector3 value) {
        double x = sanitize(value.x()), y = sanitize(value.y()), z = sanitize(value.z());
        double length = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (length < 3.051944088384301e-5) {
            output.writeByte(0);
            return;
        }
        long scale = (long) Math.ceil(length);
        boolean continued = (scale & 3) != scale;
        long marker = continued ? scale & 3 | 4 : scale;
        long bits = marker | pack(x / scale) << 3 | pack(y / scale) << 18 | pack(z / scale) << 33;
        output.writeByte((byte) bits);
        output.writeByte((byte) (bits >> 8));
        output.writeInt((int) (bits >> 16));
        if (continued) WireCodecs.VAR_INT.encode(output, (int) (scale >> 2));
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0 : Math.clamp(value, -1.7179869183e10, 1.7179869183e10);
    }

    private static long pack(double value) {
        return Math.round((value * 0.5 + 0.5) * 32766);
    }

    private static double unpack(long value) {
        return Math.min(value & 32767, 32766.0) * 2 / 32766 - 1;
    }
}
