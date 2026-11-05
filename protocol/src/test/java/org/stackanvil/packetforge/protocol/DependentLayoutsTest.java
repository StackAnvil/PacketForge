package org.stackanvil.packetforge.protocol;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.packet.game.*;
import static org.junit.jupiter.api.Assertions.*;

class DependentLayoutsTest {
    @Test void packedChangesRejectNegativeAndImpossibleCountsBeforeArrayAllocation() {
        for (int count : new int[]{-1, Integer.MAX_VALUE, 1}) {
            var buffer = Unpooled.buffer();
            try {
                buffer.writeLong(0); WireCodecs.VAR_INT.encode(buffer, count);
                assertThrows(IllegalArgumentException.class, () -> ClientboundSectionBlocksUpdatePacket.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }
    @Test void equipmentContinuationRequiresAnotherCompleteEntry() {
        var buffer = Unpooled.buffer();
        try {
            WireCodecs.VAR_INT.encode(buffer, 123);
            buffer.writeByte(128); buffer.writeByte(0);
            assertThrows(IndexOutOfBoundsException.class, () -> ClientboundSetEquipmentPacket.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
