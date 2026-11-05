package org.stackanvil.packetforge.generator;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrySchemaTest {
    @TempDir Path sources;
    private void source(String name, String body) throws Exception {
        Path path = sources.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }
    private Analyzer fixture(String secondCodec) throws Exception {
        source("net.minecraft.core.registries.BuiltInRegistries", """
            import net.minecraft.fixture.Entries;
            class BuiltInRegistries { static final Object TEST = register(Entries::bootstrap); }
            """);
        source("net.minecraft.fixture.Entries", """
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.codec.ByteBufCodecs;
            class Entries {
                static final Entry FIRST = register("first", ByteBufCodecs.VAR_INT);
                static final Entry SECOND = register("second", %s);
                static Entry register(String name, Object codec) {
                    return Registry.register(BuiltInRegistries.TEST, Identifier.withDefaultNamespace(name), new Entry(codec));
                }
                static Entry bootstrap(Object registry) { return FIRST; }
            }
            """.formatted(secondCodec));
        source("net.minecraft.fixture.Entry", """
            import net.minecraft.network.codec.ByteBufCodecs;
            record Entry(Object codec) {
                static final Object STREAM_CODEC = ByteBufCodecs.registry(Registries.TEST).dispatch(Entry::type, Entry::streamCodec);
                Object streamCodec() { return Objects.requireNonNull(this.codec); }
            }
            """);
        return new Analyzer(sources);
    }
    @Test void recoversCapturedPayloadsAndProvenNullRejectionsInRegistryOrder() throws Exception {
        Analyzer analyzer = fixture("null");
        var source = analyzer.index.source("net.minecraft.fixture.Entry");
        var wire = analyzer.codec(StaticJavaParser.parseExpression("Entry.STREAM_CODEC"), source);
        var dispatch = analyzer.dispatches.values().iterator().next();
        assertEquals("dispatch:" + dispatch.name(), wire.shape());
        assertEquals(java.util.List.of("0", "1"), dispatch.cases().stream().map(Analyzer.DispatchCase::key).toList());
        assertEquals(java.util.List.of("minecraft:first", "minecraft:second"), dispatch.cases().stream().map(Analyzer.DispatchCase::name).toList());
        assertEquals("VAR_INT", dispatch.cases().getFirst().value().shape());
        assertTrue(dispatch.cases().getLast().value().shape().startsWith("REJECTED:"));
        assertTrue(analyzer.registryGaps.isEmpty());
    }
    @Test void refusesAnIncompleteRegistryAndReportsTheMissingPayloadId() throws Exception {
        Analyzer analyzer = fixture("Unknown.CODEC");
        var source = analyzer.index.source("net.minecraft.fixture.Entry");
        assertThrows(SourceIndex.Unsupported.class, () -> analyzer.codec(StaticJavaParser.parseExpression("Entry.STREAM_CODEC"), source));
        assertTrue(analyzer.dispatches.isEmpty());
        var gaps = analyzer.registryGaps.values().iterator().next();
        assertEquals(1, gaps.size(), analyzer.registryGaps.toString());
        assertEquals(1, gaps.getFirst().id());
        assertEquals("minecraft:second", gaps.getFirst().name());
    }
}
