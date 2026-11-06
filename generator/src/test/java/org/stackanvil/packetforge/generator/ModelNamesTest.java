package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

class ModelNamesTest {
    private static final String TYPES = "org.stackanvil.packetforge.protocol.type.fixture.";

    private static Model filtered(int hash, int limit) {
        var wire = new Wire("String", "WireCodecs.string(" + limit + ")", "UTF8");
        return new Model(
                "fixture.Filterable#" + hash,
                TYPES + "Filterable_" + hash,
                List.of(new Field("raw", "T", wire, wire)),
                List.of(),
                false);
    }

    @Test
    void distinguishesLimitsWithoutDependingOnInputOrderOrHashValues() {
        var a = filtered(1, 32);
        var b = filtered(2, 1024);
        var names = new ModelNames(List.of(a, b));
        assertEquals(TYPES + "FilterableString32", names.names().get(a.generatedName()));
        assertEquals(TYPES + "FilterableString1024", names.names().get(b.generatedName()));
        assertEquals(names.names(), new ModelNames(List.of(b, a)).names());
        var differentHash = filtered(999, 32);
        assertEquals(
                TYPES + "FilterableString32",
                new ModelNames(List.of(differentHash, b)).names().get(differentHash.generatedName()));
    }

    @Test
    void keepsSourceNumbersAndMovesCodecVariantsBeforeThePacketSuffix() {
        var frame = new Model("fixture.Frame_2", TYPES + "Frame_2", List.of(), List.of(), false);
        var packet = new Model(
                "fixture.ShowPacket#CONTEXT_FREE_STREAM_CODEC",
                TYPES + "ShowPacket_CONTEXT_FREE_STREAM_CODEC",
                List.of(),
                List.of(),
                true,
                List.of(),
                null,
                "",
                "CONTEXT_FREE_STREAM_CODEC");
        var names = new ModelNames(List.of(frame, packet));
        assertEquals(TYPES + "Frame2", names.names().get(frame.generatedName()));
        assertEquals(TYPES + "ShowContextFreePacket", names.names().get(packet.generatedName()));
    }

    @Test
    void rejectsSourceNameCollisionsAndIndistinguishableSchemas() {
        var a = new Model("fixture.Outer.Inner", TYPES + "Outer_Inner", List.of(), List.of(), false);
        var b = new Model("fixture.OuterInner", TYPES + "OuterInner", List.of(), List.of(), false);
        assertThrows(Unsupported.class, () -> new ModelNames(List.of(a, b)));
        assertThrows(Unsupported.class, () -> new ModelNames(List.of(filtered(1, 32), filtered(2, 32))));
    }
}
