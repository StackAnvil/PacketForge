package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.ParseProblemException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelJavaEmitterTest {
    @Test
    void invalidModelFragmentsIdentifyTheClassBeforeWritingSource() {
        var broken = new Analyzer.Wire("String", "invalid(", "fixture");
        var model = new Analyzer.Model(
                "fixture.Broken",
                "org.stackanvil.packetforge.protocol.type.fixture.Broken",
                List.of(new Analyzer.Field("value", "String", broken, broken)),
                List.of(),
                false);

        var failure = assertThrows(IOException.class, () -> new ModelJavaEmitter().emit(model));
        assertEquals("Cannot emit generated Java " + model.generatedName(), failure.getMessage());
        assertInstanceOf(ParseProblemException.class, failure.getCause());
    }
}
