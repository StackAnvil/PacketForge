package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandArgumentAnalyzerTest {
    @TempDir Path temporary;

    private void source(String name, String body) throws Exception {
        Path file = temporary.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }

    private static final String BOOTSTRAP =
            """
        import net.minecraft.fixture.SimpleInfo;
        class ArgumentTypeInfos {
            static Info register(Registry registry, String id, Class type, Info info) {
                BY_CLASS.put(type, info);
                return Registry.register(registry, id, info);
            }
            static Info bootstrap(Registry registry) {
                register(registry, "empty", Empty.class, SingletonArgumentInfo.contextFree(Empty::new));
                return register(registry, "test:number", Number.class, new SimpleInfo());
            }
        }
        """;

    private Analyzer fixture(String bootstrap) throws Exception {
        source(
                "net.minecraft.core.registries.BuiltInRegistries",
                """
            import net.minecraft.commands.synchronization.ArgumentTypeInfos;
            class BuiltInRegistries { static final Object COMMAND_ARGUMENT_TYPE = register(ArgumentTypeInfos::bootstrap); }
            """);
        source("net.minecraft.commands.synchronization.ArgumentTypeInfos", bootstrap);
        source(
                "net.minecraft.commands.synchronization.SingletonArgumentInfo",
                """
            class SingletonArgumentInfo {
                Template deserializeFromNetwork(FriendlyByteBuf input) { return this.template; }
                void serializeToNetwork(Template value, FriendlyByteBuf output) {}
            }
            """);
        source(
                "net.minecraft.fixture.SimpleInfo",
                """
            class SimpleInfo {
                SimpleInfo.Template deserializeFromNetwork(FriendlyByteBuf input) { return new SimpleInfo.Template(input.readVarInt()); }
                void serializeToNetwork(SimpleInfo.Template template, FriendlyByteBuf output) { output.writeVarInt(template.amount); }
                record Template(int amount) {}
            }
            """);
        return new Analyzer(temporary);
    }

    @Test
    void recoversExactRegistryOrderAndIndependentSerializerPrograms() throws Exception {
        var analyzer = fixture(BOOTSTRAP);
        new CommandArgumentAnalyzer(analyzer).table();
        var cases = analyzer.dispatches.values().iterator().next().cases();
        assertEquals(List.of("0", "1"), cases.stream().map(Analyzer.DispatchCase::key).toList());
        assertEquals(
                List.of("minecraft:empty", "test:number"),
                cases.stream().map(Analyzer.DispatchCase::name).toList());
        assertEquals("UNIT", cases.getFirst().value().shape());
        assertEquals(
                List.of("VAR_INT"),
                analyzer.models
                        .get("net.minecraft.fixture.SimpleInfo.Template#argument")
                        .program()
                        .operations());
        assertTrue(analyzer.registryGaps.isEmpty());
    }

    @Test
    void unknownRegistrationOperationsAndDuplicateIdsCannotProducePartialTables() throws Exception {
        for (String body :
                List.of(
                        BOOTSTRAP.replace("return register(registry", "return other(registry"),
                        BOOTSTRAP.replace("test:number", "empty"),
                        BOOTSTRAP.replace(
                                "BY_CLASS.put(type, info);",
                                "BY_CLASS.put(type, info); extraRegistration(registry);"))) {
            var analyzer = fixture(body);
            assertThrows(
                    SourceIndex.Unsupported.class,
                    () -> new CommandArgumentAnalyzer(analyzer).table());
            assertTrue(analyzer.dispatches.isEmpty());
        }
    }

    @Test
    void missingPayloadsRetainTheirWireIdsInDiagnosticsAndDoNotEmitATable() throws Exception {
        var analyzer = fixture(BOOTSTRAP.replace("new SimpleInfo()", "new MissingInfo()"));
        assertThrows(
                SourceIndex.Unsupported.class, () -> new CommandArgumentAnalyzer(analyzer).table());
        assertEquals(1, analyzer.registryGaps.get("COMMAND_ARGUMENT_TYPE").getFirst().id());
        assertEquals(
                "test:number",
                analyzer.registryGaps.get("COMMAND_ARGUMENT_TYPE").getFirst().name());
        assertTrue(analyzer.dispatches.isEmpty());
    }
}
