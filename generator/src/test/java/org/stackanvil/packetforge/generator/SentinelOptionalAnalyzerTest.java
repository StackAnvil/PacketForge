package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SentinelOptionalAnalyzerTest {
    @TempDir Path sources;

    private Analyzer fixture(boolean mismatched) throws Exception {
        Path file = sources.resolve("net/minecraft/fixture/Update.java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
            package net.minecraft.fixture;
            record Update(String label, int width) {
                static void write(FriendlyByteBuf output, Optional<Update> optional) {
                    if (optional.isPresent()) {
                        Update payload = optional.get();
                        %s
                    } else { output.writeByte(0); }
                }
                static Optional<Update> read(FriendlyByteBuf input) {
                    int width = input.readUnsignedByte();
                    if (width > 0) {
                        String label = input.readUtf();
                        return Optional.of(new Update(label, width));
                    } else { return Optional.empty(); }
                }
            }
            """
                        .formatted(
                                mismatched
                                        ? "output.writeUtf(payload.label); output.writeByte(payload.width);"
                                        : "output.writeByte(payload.width); output.writeUtf(payload.label);"));
        return new Analyzer(sources);
    }

    @Test
    void recoversPhysicalOrderWhenTheConstructorHasADifferentParameterOrder() throws Exception {
        Analyzer analyzer = fixture(false);
        var source = analyzer.index.source("net.minecraft.fixture.Update");
        var wire =
                new SentinelOptionalAnalyzer(analyzer)
                        .lower(
                                StaticJavaParser.parseExpression(
                                                "StreamCodec.of(Update::write, Update::read)")
                                        .asMethodCallExpr(),
                                source);
        assertTrue(wire.type().startsWith("java.util.Optional<"));
        assertEquals(
                java.util.List.of("width", "label"),
                analyzer.models.get("net.minecraft.fixture.Update#sentinel").fields().stream()
                        .map(Analyzer.Field::name)
                        .toList());
    }

    @Test
    void refusesAWriterThatUsesADifferentFieldOrder() throws Exception {
        Analyzer analyzer = fixture(true);
        var source = analyzer.index.source("net.minecraft.fixture.Update");
        assertThrows(
                SourceIndex.Unsupported.class,
                () ->
                        new SentinelOptionalAnalyzer(analyzer)
                                .lower(
                                        StaticJavaParser.parseExpression(
                                                        "StreamCodec.of(Update::write, Update::read)")
                                                .asMethodCallExpr(),
                                        source));
        assertTrue(analyzer.models.isEmpty());
    }
}
