package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementCodecsTest {
    @Test
    void roundTripsLinearAndTimedMovementWithoutOwningTheOuterCount() {
        for (MovementDelta value :
                List.of(
                        new MovementDelta.Linear(Short.MIN_VALUE, (short) 0, Short.MAX_VALUE),
                        new MovementDelta.Stepped(
                                List.of(
                                        new MovementDelta.Step(
                                                (short) 1, (short) -2, (short) 3, 128))),
                        new MovementDelta.Stepped(
                                List.of(
                                        new MovementDelta.Step(
                                                (short) 0, Short.MIN_VALUE, Short.MAX_VALUE, -1),
                                        new MovementDelta.Step(
                                                (short) 4, (short) 5, (short) 6, 0))))) {
            var buffer = Unpooled.buffer();
            try {
                MovementCodecs.write(buffer, value);
                assertEquals(value, MovementCodecs.read(buffer, value.stepCount()));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsImpossibleCountsAndTruncatedDataBeforeAllocatingSteps() {
        var buffer = Unpooled.buffer();
        try {
            assertThrows(
                    WireDecodeException.class,
                    () -> MovementCodecs.read(buffer, Integer.MAX_VALUE));
            buffer.writeZero(6);
            assertThrows(WireDecodeException.class, () -> MovementCodecs.read(buffer, 1));
            buffer.clear().writeZero(5);
            assertThrows(IndexOutOfBoundsException.class, () -> MovementCodecs.read(buffer, 0));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new MovementDelta.Stepped(List.of()));
    }
}
