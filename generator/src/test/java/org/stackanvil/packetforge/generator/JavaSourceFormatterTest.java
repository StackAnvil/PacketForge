package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class JavaSourceFormatterTest {
    @Test
    void emittedStatementsHavePalantirIndentationAndSeparateLines() throws Exception {
        assertEquals("""
                class Sample {
                    void run() {
                        first();
                        second();
                    }
                }
                """, JavaSourceFormatter.format("Sample", "class Sample {void run(){first();second();}}"));
    }

    @Test
    void invalidGeneratedSourceIdentifiesTheClassThatFailedFormatting() {
        var failure = assertThrows(
                IOException.class, () -> JavaSourceFormatter.format("example.Broken", "class Broken { void }"));
        assertEquals("Cannot format generated Java example.Broken", failure.getMessage());
        assertNotNull(failure.getCause());
    }
}
