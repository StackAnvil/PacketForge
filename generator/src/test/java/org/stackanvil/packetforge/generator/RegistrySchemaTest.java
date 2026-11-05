package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistrySchemaTest {
    @TempDir Path sources;

    private void source(String name, String body) throws Exception {
        Path path = sources.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }

    private Analyzer fixture(String secondCodec) throws Exception {
        source(
                "net.minecraft.core.registries.BuiltInRegistries",
                """
            import net.minecraft.fixture.Entries;
            class BuiltInRegistries { static final Object TEST = register(Entries::bootstrap); }
            """);
        source(
                "net.minecraft.fixture.Entries",
                """
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
            """
                        .formatted(secondCodec));
        source(
                "net.minecraft.fixture.Entry",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record Entry(Object codec) {
                static final Object STREAM_CODEC = ByteBufCodecs.registry(Registries.TEST).dispatch(Entry::type, Entry::streamCodec);
                Object streamCodec() { return Objects.requireNonNull(this.codec); }
            }
            """);
        return new Analyzer(sources);
    }

    @Test
    void recoversCapturedPayloadsAndProvenNullRejectionsInRegistryOrder() throws Exception {
        Analyzer analyzer = fixture("null");
        var source = analyzer.index.source("net.minecraft.fixture.Entry");
        var wire = analyzer.codec(StaticJavaParser.parseExpression("Entry.STREAM_CODEC"), source);
        var dispatch = analyzer.dispatches.values().iterator().next();
        assertEquals("dispatch:" + dispatch.name(), wire.shape());
        assertEquals(
                java.util.List.of("0", "1"),
                dispatch.cases().stream().map(Analyzer.DispatchCase::key).toList());
        assertEquals(
                java.util.List.of("minecraft:first", "minecraft:second"),
                dispatch.cases().stream().map(Analyzer.DispatchCase::name).toList());
        assertEquals("VAR_INT", dispatch.cases().getFirst().value().shape());
        assertTrue(dispatch.cases().getLast().value().shape().startsWith("REJECTED:"));
        assertTrue(analyzer.registryGaps.isEmpty());
    }

    @Test
    void refusesAnIncompleteRegistryAndReportsTheMissingPayloadId() throws Exception {
        Analyzer analyzer = fixture("Unknown.CODEC");
        var source = analyzer.index.source("net.minecraft.fixture.Entry");
        assertThrows(
                SourceIndex.Unsupported.class,
                () ->
                        analyzer.codec(
                                StaticJavaParser.parseExpression("Entry.STREAM_CODEC"), source));
        assertTrue(analyzer.dispatches.isEmpty());
        var gaps = analyzer.registryGaps.values().iterator().next();
        assertEquals(1, gaps.size(), analyzer.registryGaps.toString());
        assertEquals(1, gaps.getFirst().id());
        assertEquals("minecraft:second", gaps.getFirst().name());
    }

    @Test
    void resolvesNetworkAndPersistentComponentBuilderDefaults() throws Exception {
        source(
                "net.minecraft.core.registries.BuiltInRegistries",
                """
            import net.minecraft.core.component.DataComponents;
            class BuiltInRegistries { static final Object DATA_COMPONENT_TYPE = register(r -> DataComponents.FIRST); }
            """);
        source(
                "net.minecraft.core.component.DataComponents",
                """
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.codec.ByteBufCodecs;
            class DataComponents {
                static final Object FIRST = register("first", b -> b.persistent(Value.CODEC).networkSynchronized(ByteBufCodecs.VAR_INT).cacheEncoding());
                static final Object SECOND = register("second", b -> b.persistent(Value.CODEC));
                static Object register(String id, UnaryOperator<DataComponentType.Builder> builder) {
                    return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, builder.apply(DataComponentType.builder()).build());
                }
            }
            """);
        source(
                "net.minecraft.core.component.DataComponentType",
                "interface DataComponentType { Object streamCodec(); }");
        source(
                "net.minecraft.core.component.TypedDataComponent",
                "record TypedDataComponent(Object type, Object value) {}");
        Analyzer analyzer = new Analyzer(sources);
        analyzer.codec(
                StaticJavaParser.parseExpression("TypedDataComponent.STREAM_CODEC"),
                analyzer.index.source("net.minecraft.core.component.TypedDataComponent"));
        var dispatch = analyzer.dispatches.values().iterator().next();
        assertEquals(2, dispatch.cases().size());
        assertEquals("VAR_INT", dispatch.cases().getFirst().value().shape());
        assertEquals(
                "org.stackanvil.packetforge.commons.NbtTag",
                dispatch.cases().getLast().value().type());
        assertTrue(dispatch.cases().getLast().value().shape().contains("Value.CODEC"));
    }

    @Test
    void specializesGenericCodecFactoriesAndKeepsRecursiveValueTypes() throws Exception {
        source(
                "net.minecraft.fixture.Values",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            class Values { static Object first = Filterable.streamCodec(ByteBufCodecs.VAR_INT); }
            """);
        source(
                "net.minecraft.fixture.Filterable",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record Filterable<T>(T raw, Optional<T> filtered) {
                static Object streamCodec(Object valueCodec) {
                    return StreamCodec.composite(valueCodec, Filterable::raw, valueCodec.apply(ByteBufCodecs::optional), Filterable::filtered, Filterable::new);
                }
            }
            """);
        source(
                "net.minecraft.fixture.Node",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record Node(int value, Optional<Node> child) {
                static Object STREAM_CODEC = StreamCodec.recursive(self -> StreamCodec.composite(ByteBufCodecs.VAR_INT, Node::value, self.apply(ByteBufCodecs::optional), Node::child, Node::new));
            }
            """);
        Analyzer analyzer = new Analyzer(sources);
        var context = analyzer.index.source("net.minecraft.fixture.Values");
        var resolver = new StaticCodecResolver(analyzer);
        var first =
                resolver.codec(
                        new StaticCodecResolver.Value(
                                StaticJavaParser.parseExpression(
                                        "Filterable.streamCodec(ByteBufCodecs.VAR_INT)"),
                                context));
        var second =
                resolver.codec(
                        new StaticCodecResolver.Value(
                                StaticJavaParser.parseExpression(
                                        "Filterable.streamCodec(ByteBufCodecs.STRING_UTF8)"),
                                context));
        assertNotEquals(first.type(), second.type());
        assertEquals(
                "VAR_INT",
                analyzer.models.get(first.shape().substring(6)).fields().getFirst().read().shape());
        assertEquals(
                "UTF8",
                analyzer.models
                        .get(second.shape().substring(6))
                        .fields()
                        .getFirst()
                        .read()
                        .shape());
        var recursive =
                analyzer.codec(StaticJavaParser.parseExpression("Node.STREAM_CODEC"), context);
        var model = analyzer.models.get(recursive.shape().substring(6));
        assertEquals(
                "java.util.Optional<" + model.generatedName() + ">",
                model.fields().getLast().read().type());
    }

    @Test
    void rejectsPacketsThatReachAnUnfinishedRecursiveTable() throws Exception {
        Analyzer analyzer = fixture("Unknown.CODEC");
        source(
                "net.minecraft.fixture.RecursiveValue",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record RecursiveValue(Optional<Entry> child) {
                static Object STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.optional(Entry.STREAM_CODEC), RecursiveValue::child, RecursiveValue::new);
            }
            """);
        // The first entry refers back to its table before the unknown second entry fails.
        source(
                "net.minecraft.fixture.Entries",
                """
            import net.minecraft.core.registries.BuiltInRegistries;
            class Entries {
                static Object FIRST = register("first", RecursiveValue.STREAM_CODEC);
                static Object SECOND = register("second", Unknown.CODEC);
                static Object register(String id, Object codec) { return Registry.register(BuiltInRegistries.TEST, id, new Entry(codec)); }
                static Object bootstrap(Object registry) { return FIRST; }
            }
            """);
        source(
                "net.minecraft.network.protocol.game.GamePacketTypes",
                """
            class GamePacketTypes { static Object FIRST = createClientbound("first"); static Object SECOND = createClientbound("second"); }
            """);
        source(
                "net.minecraft.network.protocol.game.GameProtocols",
                """
            class GameProtocols {
                static Object CLIENTBOUND = ProtocolInfoBuilder.clientboundProtocol(ConnectionProtocol.PLAY,
                    b -> b.addPacket(GamePacketTypes.FIRST, FirstPacket.STREAM_CODEC).addPacket(GamePacketTypes.SECOND, SecondPacket.STREAM_CODEC));
            }
            """);
        source(
                "net.minecraft.network.protocol.game.FirstPacket",
                """
            import net.minecraft.fixture.Entry;
            record FirstPacket(Entry value) implements Packet<Listener> {
                static Object STREAM_CODEC = StreamCodec.composite(Entry.STREAM_CODEC, FirstPacket::value, FirstPacket::new);
            }
            """);
        source(
                "net.minecraft.network.protocol.game.SecondPacket",
                """
            import net.minecraft.fixture.RecursiveValue;
            record SecondPacket(RecursiveValue value) implements Packet<Listener> {
                static Object STREAM_CODEC = StreamCodec.composite(RecursiveValue.STREAM_CODEC, SecondPacket::value, SecondPacket::new);
            }
            """);
        var registrations = analyzer.analyze();
        assertEquals(
                java.util.List.of(false, false),
                registrations.stream().map(Analyzer.Registration::supported).toList());
        assertTrue(registrations.stream().allMatch(r -> !r.reason().isBlank()));
        assertTrue(analyzer.models.containsKey("net.minecraft.fixture.RecursiveValue"));
        assertTrue(
                registrations.getLast().reason().startsWith("Unresolved recursive codec table:"),
                registrations.getLast().reason());
    }
}
