package org.stackanvil.packetforge.commons;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorCodecsTest {
    @Test void compactVectorsHaveStableCanonicalBytesAtScaleBoundaries() {
        for (var value : new Vector3[]{new Vector3(0, 0, 0), new Vector3(1e-6, -1e-6, 0), new Vector3(1, -1, 0.5), new Vector3(4, -3, 2), new Vector3(1000000, 0, -1000000), new Vector3(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)}) {
            var first = Unpooled.buffer(); var second = Unpooled.buffer();
            try {
                VectorCodecs.LOW_PRECISION.encode(first, value);
                byte[] expected = new byte[first.readableBytes()]; first.getBytes(0, expected);
                var decoded = VectorCodecs.LOW_PRECISION.decode(first);
                assertTrue(Double.isFinite(decoded.x()) && Double.isFinite(decoded.y()) && Double.isFinite(decoded.z()));
                VectorCodecs.LOW_PRECISION.encode(second, decoded);
                byte[] actual = new byte[second.readableBytes()]; second.readBytes(actual); assertArrayEquals(expected, actual);
                assertFalse(first.isReadable());
            } finally { first.release(); second.release(); }
        }
    }
}
