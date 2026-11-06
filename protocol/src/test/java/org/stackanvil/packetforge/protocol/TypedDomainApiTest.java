package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.WireCodecs;
import org.stackanvil.packetforge.commons.status.ServerStatus;
import org.stackanvil.packetforge.protocol.packet.status.ClientboundStatusResponsePacket;
import org.stackanvil.packetforge.protocol.payload.SlotDisplayPayload;
import org.stackanvil.packetforge.protocol.type.network.HashedPatchMap;
import org.stackanvil.packetforge.protocol.type.world.item.component.TooltipDisplay;

class TypedDomainApiTest {
    @Test
    void statusDescriptionsAndAllComponentReferenceFieldsExposeTypedValues() {
        var status = new ClientboundStatusResponsePacket(new ServerStatus(Component.text("Server")));
        assertEquals(Component.text("Server"), status.status().description());
        var display = SlotDisplayPayload.onlyWithComponent(SlotDisplayPayload.item(1), DataComponentTypes.DAMAGE);
        assertSame(DataComponentTypes.DAMAGE, display.value().component());
        var hidden = new TooltipDisplay(false, Set.of(DataComponentTypes.DAMAGE));
        assertTrue(hidden.hiddenComponents().contains(DataComponentTypes.DAMAGE));
    }

    @Test
    void typedHashKeysRetainTheirIdsAndLeaveChecksumsAsFixedWidthIntegers() {
        int checksum = 0x89abcdef;
        var value =
                new HashedPatchMap(Map.of(DataComponentTypes.DAMAGE, checksum), Set.of(DataComponentTypes.CUSTOM_NAME));
        var buffer = Unpooled.buffer();
        try {
            HashedPatchMap.CODEC.encode(buffer, value);
            var decoded = HashedPatchMap.CODEC.decode(buffer.duplicate());
            assertEquals(value, decoded);
            assertEquals(1, WireCodecs.VAR_INT.decode(buffer));
            assertEquals(DataComponentTypes.DAMAGE.id(), WireCodecs.VAR_INT.decode(buffer));
            assertEquals(checksum, WireCodecs.INT.decode(buffer));
            assertEquals(1, WireCodecs.VAR_INT.decode(buffer));
            assertEquals(DataComponentTypes.CUSTOM_NAME.id(), WireCodecs.VAR_INT.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
