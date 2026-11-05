package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stackanvil.packetforge.commons.*;

class CodecVariantAnalyzerTest {
    @TempDir Path temporary;

    private void source(String name, String body) throws Exception {
        Path path =
                temporary.resolve("sources/net/minecraft/network/protocol/game/" + name + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package net.minecraft.network.protocol.game;\n" + body);
    }

    private Analyzer fixture(boolean context, boolean extraRead) throws Exception {
        source(
                "GamePacketTypes",
                "class GamePacketTypes { static Object FIRST = createServerbound(\"first\"); static Object SECOND = createServerbound(\"second\"); }");
        source(
                "VariantPacket",
                """
            import net.minecraft.network.codec.ByteBufCodecs;
            record VariantPacket(String text) implements Packet<Listener> {
                static Object STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(1024), VariantPacket::text, VariantPacket::new);
                static Object SMALL_STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(4), VariantPacket::text, VariantPacket::new);
            }
            """);
        String registrations =
                context
                        ? "b.addPacket(GamePacketTypes.FIRST, VariantPacket.STREAM_CODEC, GATE).addPacket(GamePacketTypes.SECOND, VariantPacket.STREAM_CODEC, choose(c -> c.longText() ? VariantPacket.STREAM_CODEC : VariantPacket.SMALL_STREAM_CODEC))"
                        : "b.addPacket(GamePacketTypes.FIRST, VariantPacket.STREAM_CODEC).addPacket(GamePacketTypes.SECOND, VariantPacket.SMALL_STREAM_CODEC)";
        source(
                "GameProtocols",
                """
            class GameProtocols {
                interface Context { boolean allow(); boolean longText(); }
                static Object GATE = (original, context) -> new StreamCodec() {
                    Object decode(FriendlyByteBuf input) {
                        if (!context.allow()) { throw new SkipPacketDecoderException("blocked"); }
                        else { %s return original.decode(input); }
                    }
                    void encode(FriendlyByteBuf output, Object value) {
                        if (!context.allow()) { throw new SkipPacketEncoderException("blocked"); }
                        original.encode(output, value);
                    }
                };
                static Object choose(Function getter) {
                    return (original, context) -> new StreamCodec() {
                        Object decode(FriendlyByteBuf input) { return getter.apply(context).decode(input); }
                        void encode(FriendlyByteBuf output, Object value) { getter.apply(context).encode(output, value); }
                    };
                }
                static Object TEMPLATE = ProtocolInfoBuilder.contextServerboundProtocol(ConnectionProtocol.PLAY, b -> %s);
            }
            """
                        .formatted(extraRead ? "input.readByte();" : "", registrations));
        return new Analyzer(temporary.resolve("sources"));
    }

    private URLClassLoader compile(Analyzer analyzer, List<Analyzer.Registration> registrations)
            throws Exception {
        Path generated = temporary.resolve("generated"), classes = temporary.resolve("classes");
        Files.createDirectories(classes);
        new Emitter()
                .emit(generated, Map.of("version", "fixture"), 777, registrations, analyzer.models);
        var compiler = ToolProvider.getSystemJavaCompiler();
        String classpath =
                String.join(
                        java.io.File.pathSeparator,
                        List.of(Packet.class, ByteBuf.class, io.netty.util.ReferenceCounted.class)
                                .stream()
                                .map(
                                        c ->
                                                Path.of(
                                                                java.net.URI.create(
                                                                        c.getProtectionDomain()
                                                                                .getCodeSource()
                                                                                .getLocation()
                                                                                .toString()))
                                                        .toString())
                                .toList());
        try (var manager = compiler.getStandardFileManager(null, null, null);
                var files = Files.walk(generated.resolve("java"))) {
            assertTrue(
                    compiler.getTask(
                                    null,
                                    manager,
                                    null,
                                    List.of("-classpath", classpath, "-d", classes.toString()),
                                    null,
                                    manager.getJavaFileObjectsFromPaths(
                                            files.filter(p -> p.toString().endsWith(".java"))
                                                    .toList()))
                            .call());
        }
        return new URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void namedFieldsProduceDifferentUsablePacketTypesWithReservedWireIds() throws Exception {
        Analyzer analyzer = fixture(false, false);
        var registrations = analyzer.analyze();
        assertTrue(registrations.stream().allMatch(Analyzer.Registration::supported));
        assertEquals(List.of(0, 1), registrations.stream().map(Analyzer.Registration::id).toList());
        assertNotEquals(
                registrations.getFirst().generatedClass(),
                registrations.getLast().generatedClass());
        assertEquals(
                "SMALL_STREAM_CODEC",
                analyzer.models
                        .get("net.minecraft.network.protocol.game.VariantPacket#SMALL_STREAM_CODEC")
                        .sourceCodecField());
        try (var loader = compile(analyzer, registrations)) {
            PacketRegistry registry =
                    (PacketRegistry)
                            loader.loadClass(
                                            "org.stackanvil.packetforge.protocol.GeneratedProtocol")
                                    .getMethod("registry")
                                    .invoke(null);
            var small =
                    registry.entries().values().stream()
                            .filter(e -> e.key().id() == 1)
                            .findFirst()
                            .orElseThrow();
            ByteBuf buffer = Unpooled.buffer();
            try {
                Packet packet =
                        (Packet) small.type().getConstructor(String.class).newInstance("abcd");
                registry.encode(small.key(), packet, buffer);
                Packet decoded = registry.decode(small.key().state(), small.key().flow(), buffer);
                assertEquals("abcd", small.type().getMethod("text").invoke(decoded));
                Packet tooLarge =
                        (Packet) small.type().getConstructor(String.class).newInstance("abcde");
                int start = buffer.writerIndex();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.encode(small.key(), tooLarge, buffer));
                assertEquals(start, buffer.writerIndex());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void generatedModifiersRecheckTheSameContextForEachOperation() throws Exception {
        Analyzer analyzer = fixture(true, false);
        var registrations = analyzer.analyze();
        assertTrue(
                registrations.stream().allMatch(Analyzer.Registration::supported),
                registrations.toString());
        try (var loader = compile(analyzer, registrations)) {
            Class<?>
                    protocol =
                            loader.loadClass(
                                    "org.stackanvil.packetforge.protocol.GeneratedProtocol"),
                    context =
                            loader.loadClass(
                                    "org.stackanvil.packetforge.protocol.GeneratedProtocol$Context");
            var allow = new java.util.concurrent.atomic.AtomicBoolean(false);
            var longText = new java.util.concurrent.atomic.AtomicBoolean(false);
            Object flags =
                    java.lang.reflect.Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {context},
                            (proxy, method, args) ->
                                    method.getName().equals("allow")
                                            ? allow.get()
                                            : longText.get());
            PacketRegistry registry =
                    (PacketRegistry) protocol.getMethod("registry", context).invoke(null, flags);
            Packet packet =
                    (Packet)
                            loader.loadClass(registrations.getFirst().generatedClass())
                                    .getConstructor(String.class)
                                    .newInstance("abcde");
            var gate =
                    new PacketRegistry.Key(
                            PacketRegistry.State.PLAY, PacketRegistry.Flow.SERVERBOUND, 0);
            var selected =
                    new PacketRegistry.Key(
                            PacketRegistry.State.PLAY, PacketRegistry.Flow.SERVERBOUND, 1);
            ByteBuf buffer = Unpooled.buffer();
            try {
                assertThrows(
                        SkippedPacketException.class, () -> registry.encode(gate, packet, buffer));
                assertEquals(0, buffer.writerIndex());
                allow.set(true);
                registry.encode(gate, packet, buffer);
                assertEquals(packet, registry.decode(gate.state(), gate.flow(), buffer));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.encode(selected, packet, buffer));
                longText.set(true);
                registry.encode(selected, packet, buffer);
                assertEquals(packet, registry.decode(selected.state(), selected.flow(), buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void unknownModifierOperationsAndMissingNamedFieldsStayUnsupported() throws Exception {
        Analyzer analyzer = fixture(true, true);
        var registrations = analyzer.analyze();
        assertFalse(registrations.getFirst().supported());
        assertTrue(registrations.getLast().supported());
        assertEquals(1, registrations.getLast().id());
        assertThrows(
                SourceIndex.Unsupported.class,
                () ->
                        analyzer.model(
                                "net.minecraft.network.protocol.game.VariantPacket",
                                "MISSING_CODEC"));
    }

    @Test
    void unknownRegistrationArityFailsBeforeItCanAssignWireIds() throws Exception {
        Analyzer analyzer = fixture(false, false);
        source(
                "GameProtocols",
                """
            class GameProtocols { static Object TEMPLATE = ProtocolInfoBuilder.serverboundProtocol(ConnectionProtocol.PLAY,
                b -> b.addPacket(GamePacketTypes.FIRST, VariantPacket.STREAM_CODEC, GATE, 1)); }
            """);
        var failure = assertThrows(SourceIndex.Unsupported.class, analyzer::analyze);
        assertEquals("Unknown packet registration arity", failure.getMessage());
    }
}
