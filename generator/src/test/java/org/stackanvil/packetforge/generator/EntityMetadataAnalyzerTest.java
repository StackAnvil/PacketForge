package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityMetadataAnalyzerTest {
    @TempDir Path sources;

    private void source(String name, String body) throws Exception {
        Path path = sources.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }

    private Analyzer fixture(String firstCodec) throws Exception {
        source(
                "net.minecraft.network.syncher.EntityDataSerializer",
                """
            interface EntityDataSerializer {
                static Object forValueType(Object codec) { return () -> codec; }
            }
            """);
        source(
                "net.minecraft.network.syncher.EntityDataSerializers",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            class EntityDataSerializers {
                static Object FIRST = EntityDataSerializer.forValueType(%s);
                static Object SECOND = EntityDataSerializer.forValueType(ByteBufCodecs.BOOL);
                static { registerSerializer(SECOND); registerSerializer(FIRST); }
            }
            """
                        .formatted(firstCodec));
        return new Analyzer(sources);
    }

    @Test
    void idsFollowRegistrationOrderInsteadOfFieldDeclarationOrder() throws Exception {
        Analyzer analyzer = fixture("ByteBufCodecs.VAR_INT");
        new EntityMetadataAnalyzer(analyzer).table();
        var table = analyzer.dispatches.values().iterator().next();
        assertEquals(
                java.util.List.of("SECOND", "FIRST"),
                table.cases().stream().map(Analyzer.DispatchCase::name).toList());
        assertEquals(
                java.util.List.of("0", "1"),
                table.cases().stream().map(Analyzer.DispatchCase::key).toList());
        assertEquals(
                java.util.List.of("BOOL", "VAR_INT"),
                table.cases().stream().map(c -> c.value().shape()).toList());
    }

    @Test
    void missingSerializerKeepsItsWireIdAndPreventsAPartialTable() throws Exception {
        Analyzer analyzer = fixture("Unknown.CODEC");
        assertThrows(
                SourceIndex.Unsupported.class, () -> new EntityMetadataAnalyzer(analyzer).table());
        assertTrue(analyzer.dispatches.isEmpty());
        var missing = analyzer.registryGaps.get("ENTITY_DATA_SERIALIZERS");
        assertEquals(1, missing.size());
        assertEquals(1, missing.getFirst().id());
        assertEquals("FIRST", missing.getFirst().name());
    }

    @Test
    void rejectsExtraRegistrationOperations() throws Exception {
        Analyzer analyzer = fixture("ByteBufCodecs.VAR_INT");
        source(
                "net.minecraft.network.syncher.EntityDataSerializers",
                """
            class EntityDataSerializers { static { for (int i = 0; i < 2; i++) registerSerializer(FIRST); } }
            """);
        assertThrows(
                SourceIndex.Unsupported.class, () -> new EntityMetadataAnalyzer(analyzer).table());
    }
}
