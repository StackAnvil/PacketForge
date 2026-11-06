package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class IdentifierCodecTest {
    @Test
    void decodesAdventureKeysAndDefaultsTheNamespace() {
        for (String value : List.of("", ":", "stone", ":stone", "minecraft:", "test:a/b._-0", "...:..")) {
            var buffer = Unpooled.buffer();
            try {
                WireCodecs.STRING.encode(buffer, value);
                assertEquals(Key.key(value), WireCodecs.IDENTIFIER.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsInvalidIdentifiersWithTheWireValidationException() {
        for (String value :
                List.of("..:path", "TEST:path", "test:Path", "test:a b", "test:a:b", "a/b:c", "test:é", "test:\n")) {
            var buffer = Unpooled.buffer();
            try {
                WireCodecs.STRING.encode(buffer, value);
                assertThrows(IllegalArgumentException.class, () -> WireCodecs.IDENTIFIER.decode(buffer), value);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void writesTheKeyValueIncludingAnExplicitEmptyNamespace() {
        for (Key value : List.of(Key.key("stone"), Key.key("test", "a/b"), Key.key("", "path"), Key.key("test", ""))) {
            var buffer = Unpooled.buffer();
            try {
                WireCodecs.IDENTIFIER.encode(buffer, value);
                assertEquals(value.asString(), WireCodecs.STRING.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void retainsTheStringLimitInBothDirections() {
        var buffer = Unpooled.buffer();
        try {
            Key maximum = Key.key("test", "a".repeat(32762));
            WireCodecs.IDENTIFIER.encode(buffer, maximum);
            assertEquals(maximum, WireCodecs.IDENTIFIER.decode(buffer));
            buffer.clear();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WireCodecs.IDENTIFIER.encode(buffer, Key.key("test", "a".repeat(32763))));
            buffer.clear();
            WireCodecs.string(32768).encode(buffer, "a".repeat(32768));
            assertThrows(IllegalArgumentException.class, () -> WireCodecs.IDENTIFIER.decode(buffer));
            buffer.clear();
            WireCodecs.STRING.encode(buffer, "a".repeat(32767));
            Key decoded = WireCodecs.IDENTIFIER.decode(buffer);
            assertEquals("minecraft", decoded.namespace());
            assertEquals(32767, decoded.value().length());
            buffer.clear();
            assertThrows(IllegalArgumentException.class, () -> WireCodecs.IDENTIFIER.encode(buffer, decoded));
        } finally {
            buffer.release();
        }
    }
}
