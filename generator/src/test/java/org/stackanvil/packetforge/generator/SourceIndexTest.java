package org.stackanvil.packetforge.generator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourceIndexTest {
    @TempDir Path temporary;
    @Test void bundledLibraryEnumsGetStandaloneNamesAndRetainTheirDeclaredOrder() throws Exception {
        Path file = temporary.resolve("com/mojang/fixture/External.java"); Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.mojang.fixture; class External { enum Kind { SECOND, FIRST } }");
        var analyzer = new Analyzer(temporary); var model = analyzer.model("com.mojang.fixture.External.Kind");
        assertEquals("org.stackanvil.packetforge.protocol.type.external.com.mojang.fixture.External_Kind", model.generatedName());
        assertEquals(List.of("SECOND", "FIRST"), model.constants());
    }
}
