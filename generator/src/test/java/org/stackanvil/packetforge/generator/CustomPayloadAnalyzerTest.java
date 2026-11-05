package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomPayloadAnalyzerTest {
    @TempDir Path sources;

    private void source(String name, String body) throws Exception {
        Path path =
                sources.resolve("net/minecraft/network/protocol/common/custom/" + name + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package net.minecraft.network.protocol.common.custom;\n" + body);
    }

    private Analyzer fixture() throws Exception {
        source(
                "CustomPacketPayload",
                """
            interface CustomPacketPayload {
                static Type createType(String id) { return new Type(Identifier.withDefaultNamespace(id)); }
                record Type(Identifier id) {}
            }
            """);
        source(
                "Brand",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record Brand(String brand) {
                static Object TYPE = CustomPacketPayload.createType("test_brand");
                static Object STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(Brand::new, Brand::brand);
            }
            """);
        source("Payloads", "class Payloads {}");
        return new Analyzer(sources);
    }

    private Analyzer.Wire lower(Analyzer analyzer, String entries) {
        return new CustomPayloadAnalyzer(analyzer)
                .lower(
                        StaticJavaParser.parseExpression(
                                        "CustomPacketPayload.codec(id -> DiscardedPayload.codec(id, 64), "
                                                + entries
                                                + ")")
                                .asMethodCallExpr(),
                        analyzer.index.source(
                                "net.minecraft.network.protocol.common.custom.Payloads"));
    }

    @Test
    void recoversTheChannelIdentifierAndBoundedFallback() throws Exception {
        Analyzer analyzer = fixture();
        var wire =
                lower(
                        analyzer,
                        "List.of(new CustomPacketPayload.TypeAndCodec(Brand.TYPE, Brand.STREAM_CODEC))");
        var table = analyzer.dispatches.values().iterator().next();
        assertEquals("dispatch:" + table.name(), wire.shape());
        assertEquals("minecraft:test_brand", table.cases().getFirst().key());
        assertEquals("remainingBytes(64)", table.fallback().shape());
        assertEquals("byte[]", table.fallback().type());
    }

    @Test
    void refusesRepeatedChannelsAndDynamicTableChanges() throws Exception {
        Analyzer analyzer = fixture();
        assertThrows(
                SourceIndex.Unsupported.class,
                () ->
                        lower(
                                analyzer,
                                "List.of(new CustomPacketPayload.TypeAndCodec(Brand.TYPE, Brand.STREAM_CODEC), new CustomPacketPayload.TypeAndCodec(Brand.TYPE, Brand.STREAM_CODEC))"));
        assertTrue(analyzer.dispatches.isEmpty());
        assertThrows(
                SourceIndex.Unsupported.class,
                () -> lower(analyzer, "Util.make(List.of(), types -> { types.add(Brand.TYPE); })"));
        assertTrue(analyzer.dispatches.isEmpty());
    }
}
