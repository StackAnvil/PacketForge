package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

class ComponentReferencesTest {
    private static final Wire INT = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT");
    private static final String REFERENCE = "REGISTRY:Registries.DATA_COMPONENT_TYPE";
    private static final Dispatch TYPES =
            new Dispatch("dispatch_1", INT, List.of(), "fixture.Types", "fixture", INT, "DATA_COMPONENT_TYPE");

    private static Model model(Wire wire, Program program) {
        return new Model(
                "fixture.Model",
                "fixture.Model",
                List.of(new Field("value", "source", wire, wire)),
                List.of(),
                false,
                List.of(),
                program);
    }

    @Test
    void bindsNestedReferencesWithoutChangingOtherIntegersOrLimits() {
        var wire = new Wire(
                "java.util.Map<Integer, java.util.Set<Integer>>",
                "WireCodecs.map(WireCodecs.VAR_INT, WireCodecs.set(WireCodecs.VAR_INT, 256), 128)",
                "map<VAR_INT,set<" + REFERENCE + ">>",
                "projection");
        var field = new ComponentReferences(List.of(TYPES))
                .models(List.of(model(wire, null)))
                .getFirst()
                .fields()
                .getFirst();
        assertEquals(
                "java.util.Map<Integer, java.util.Set<org.stackanvil.packetforge.commons.DataComponentType<?>>>",
                field.read().type());
        assertEquals("map<VAR_INT,set<" + REFERENCE + ">>", field.read().shape());
        assertEquals("projection", field.read().projection());
        assertEquals(
                "WireCodecs.map(WireCodecs.VAR_INT, WireCodecs.set(ComponentCodecs.type(org.stackanvil.packetforge.protocol.GeneratedWireCodecs.dispatch_1_types()), 256), 128)",
                field.read().codec());
    }

    @Test
    void rejectsMissingRegistriesAndLayoutsThatCannotBeLoweredSafely() {
        var wire = new Wire("int", "WireCodecs.VAR_INT", REFERENCE);
        assertThrows(Unsupported.class, () -> new ComponentReferences(List.of()).models(List.of(model(wire, null))));
        var refs = new ComponentReferences(List.of(TYPES));
        var wrong = new Wire(
                "java.util.Set<Integer>", "WireCodecs.list(WireCodecs.VAR_INT, 256)", "set<" + REFERENCE + ">");
        assertThrows(Unsupported.class, () -> refs.models(List.of(model(wrong, null))));
        var program = new Program("return null;", "", List.of(), List.of());
        assertThrows(Unsupported.class, () -> refs.models(List.of(model(wire, program))));
    }
}
