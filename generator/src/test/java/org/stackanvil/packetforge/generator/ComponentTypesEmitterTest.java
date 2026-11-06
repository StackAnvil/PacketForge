package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentTypesEmitterTest {
    @Test
    void rejectsNamesThatWouldProduceAmbiguousJavaConstants() {
        var wire = new Analyzer.Wire("int", "WireCodecs.VAR_INT", "VAR_INT");
        var table = new Analyzer.Dispatch(
                "components",
                wire,
                List.of(
                        new Analyzer.DispatchCase("0", wire, "example:some-name"),
                        new Analyzer.DispatchCase("1", wire, "example:some_name")),
                "fixture",
                "fixture",
                wire,
                "DATA_COMPONENT_TYPE");
        var failure = assertThrows(SourceIndex.Unsupported.class, () -> new ComponentTypesEmitter().emit(table));
        assertEquals("Component names collide in Java: example:some_name", failure.getMessage());
    }
}
