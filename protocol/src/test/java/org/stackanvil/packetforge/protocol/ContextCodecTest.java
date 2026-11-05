package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundCommandSuggestionPacket;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundSetCreativeModeSlotPacket;

class ContextCodecTest {
    private static PacketRegistry.Key key(Class<? extends Packet> type) {
        return GeneratedProtocol.registry().entries().values().stream()
                .filter(e -> e.type() == type)
                .findFirst()
                .orElseThrow()
                .key();
    }

    @Test
    void commandReadLimitChangesWithContextWhileTheWriteLimitStaysTheSame() {
        var key = key(ServerboundCommandSuggestionPacket.class);
        for (boolean commands : new boolean[] {false, true})
            for (boolean creative : new boolean[] {false, true}) {
                var registry = GeneratedProtocol.registry(GeneratedProtocol.Context.of(commands, creative));
                for (int length : new int[] {256, 257, 32500, 32501}) {
                    var buffer = Unpooled.buffer();
                    var packet = new ServerboundCommandSuggestionPacket(42, "a".repeat(length));
                    try {
                        if (length > 32500) {
                            assertThrows(IllegalArgumentException.class, () -> registry.encode(key, packet, buffer));
                            assertEquals(0, buffer.writerIndex());
                            continue;
                        }
                        registry.encode(key, packet, buffer);
                        if (commands || length <= 256)
                            assertEquals(packet, registry.decode(key.state(), key.flow(), buffer));
                        else {
                            assertThrows(
                                    IllegalArgumentException.class,
                                    () -> registry.decode(key.state(), key.flow(), buffer));
                            assertEquals(0, buffer.readerIndex());
                        }
                    } finally {
                        buffer.release();
                    }
                }
            }
    }

    @Test
    void creativeGateRejectsBothDirectionsWithoutConsumingOrWritingBytes() {
        var key = key(ServerboundSetCreativeModeSlotPacket.class);
        var packet = new ServerboundSetCreativeModeSlotPacket((short) 1, ItemStack.EMPTY);
        for (boolean commands : new boolean[] {false, true}) {
            var allowed = GeneratedProtocol.registry(GeneratedProtocol.Context.of(commands, true));
            var denied = GeneratedProtocol.registry(GeneratedProtocol.Context.of(commands, false));
            var buffer = Unpooled.buffer();
            try {
                assertThrows(IllegalArgumentException.class, () -> denied.encode(key, packet, buffer));
                assertEquals(0, buffer.writerIndex());
                allowed.encode(key, packet, buffer);
                assertThrows(IllegalArgumentException.class, () -> denied.decode(key.state(), key.flow(), buffer));
                assertEquals(0, buffer.readerIndex());
                assertEquals(packet, allowed.decode(key.state(), key.flow(), buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void oneRegistryRechecksMutableContextOnEveryOperation() {
        var commands = new java.util.concurrent.atomic.AtomicBoolean(false);
        var creative = new java.util.concurrent.atomic.AtomicBoolean(false);
        var context = new GeneratedProtocol.Context() {
            public boolean canUseCommandBlocks() {
                return commands.get();
            }

            public boolean hasInfiniteMaterials() {
                return creative.get();
            }
        };
        var registry = GeneratedProtocol.registry(context);
        var commandKey = key(ServerboundCommandSuggestionPacket.class);
        var buffer = Unpooled.buffer();
        try {
            var packet = new ServerboundCommandSuggestionPacket(1, "a".repeat(257));
            registry.encode(commandKey, packet, buffer);
            assertThrows(
                    SkippedPacketException.class, () -> registry.decode(commandKey.state(), commandKey.flow(), buffer));
            commands.set(true);
            assertEquals(packet, registry.decode(commandKey.state(), commandKey.flow(), buffer));
            var creativeKey = key(ServerboundSetCreativeModeSlotPacket.class);
            var item = new ServerboundSetCreativeModeSlotPacket((short) 1, ItemStack.EMPTY);
            assertThrows(SkippedPacketException.class, () -> registry.encode(creativeKey, item, buffer));
            creative.set(true);
            registry.encode(creativeKey, item, buffer);
            assertEquals(item, registry.decode(creativeKey.state(), creativeKey.flow(), buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void creativeCountValidationOnlyRunsOnDecode() {
        var key = key(ServerboundSetCreativeModeSlotPacket.class);
        var registry = GeneratedProtocol.registry();
        var packet = new ServerboundSetCreativeModeSlotPacket((short) 1, new ItemStack(100, 1, ComponentPatch.EMPTY));
        var buffer = Unpooled.buffer();
        try {
            registry.encode(key, packet, buffer);
            assertThrows(IllegalArgumentException.class, () -> registry.decode(key.state(), key.flow(), buffer));
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }
}
