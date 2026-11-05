package org.stackanvil.packetforge.generator;

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
import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {
    @TempDir Path temporary;
    private void source(String name, String body) throws Exception {
        Path file = temporary.resolve("sources/net/minecraft/network/protocol/game/" + name + ".java");
        Files.createDirectories(file.getParent()); Files.writeString(file, "package net.minecraft.network.protocol.game;\n" + body);
    }
    private void fixture() throws Exception {
        source("GamePacketTypes", """
            class GamePacketTypes {
                static final PacketType<BadPacket> BAD = createClientbound("bad");
                static final PacketType<TestPacket> TEST = createClientbound("test");
                static final PacketType<DelimiterPacket> DELIMITER = createClientbound("bundle_delimiter");
            }
            """);
        source("GameProtocols", """
            class GameProtocols {
                static final Object CLIENTBOUND_TEMPLATE = ProtocolInfoBuilder.clientboundProtocol(ConnectionProtocol.PLAY,
                    b -> b.withBundlePacket(GamePacketTypes.BUNDLE, BundlePacket::new, new DelimiterPacket())
                        .addPacket(GamePacketTypes.BAD, BadPacket.STREAM_CODEC)
                        .addPacket(GamePacketTypes.TEST, TestPacket.STREAM_CODEC));
            }
            """);
        source("DelimiterPacket", """
            class DelimiterPacket implements Packet<Listener> {
                PacketType<DelimiterPacket> type() { return GamePacketTypes.DELIMITER; }
            }
            """);
        source("TestPacket", """
            record TestPacket(String text, int number) implements Packet<Listener> {
                static final StreamCodec<FriendlyByteBuf, TestPacket> STREAM_CODEC = Packet.codec(TestPacket::write, TestPacket::new);
                TestPacket(FriendlyByteBuf in) { this(in.readUtf(16), in.readVarInt()); }
                void write(FriendlyByteBuf out) { out.writeUtf(this.text); out.writeVarInt(this.number); }
            }
            """);
        source("BadPacket", """
            class BadPacket implements Packet<Listener> {
                static final StreamCodec<FriendlyByteBuf, BadPacket> STREAM_CODEC = Packet.codec(BadPacket::write, BadPacket::new);
                int number;
                BadPacket(FriendlyByteBuf in) { if (in.readBoolean()) this.number = in.readVarInt(); }
                void write(FriendlyByteBuf out) { out.writeVarInt(this.number); }
            }
            """);
    }
    @Test void recoversEnumConstructorDecodersAndRejectsAnInconsistentTypeGetter() throws Exception {
        fixture();
        String body = """
            class TestPacket implements Packet<Listener> {
                static final Object STREAM_CODEC = Packet.codec(TestPacket::write, TestPacket::new);
                final int id;
                final Operation operation;
                TestPacket(FriendlyByteBuf in) {
                    this.id = in.readVarInt();
                    TestPacket.Kind kind = in.readEnum(TestPacket.Kind.class);
                    this.operation = kind.reader.decode(in);
                }
                void write(FriendlyByteBuf out) {
                    out.writeVarInt(this.id);
                    out.writeEnum(this.operation.getType());
                    this.operation.write(out);
                }
                interface Operation { Kind getType(); void write(FriendlyByteBuf out); }
                enum Kind {
                    FIRST(TestPacket.First::new), SECOND(TestPacket.Second::new);
                    final StreamDecoder reader;
                    Kind(StreamDecoder reader) { this.reader = reader; }
                }
                record First(int number) implements Operation {
                    First(FriendlyByteBuf in) { this(in.readVarInt()); }
                    public Kind getType() { return TestPacket.Kind.FIRST; }
                    public void write(FriendlyByteBuf out) { out.writeVarInt(this.number); }
                }
                record Second(boolean flag) implements Operation {
                    Second(FriendlyByteBuf in) { this(in.readBoolean()); }
                    public Kind getType() { return TestPacket.Kind.SECOND; }
                    public void write(FriendlyByteBuf out) { out.writeBoolean(this.flag); }
                }
            }
            """;
        source("TestPacket", body);
        Analyzer analyzer = new Analyzer(temporary.resolve("sources"));
        var registrations = analyzer.analyze();
        assertTrue(registrations.get(2).supported(), registrations.get(2).reason());
        assertEquals(List.of("FIRST", "SECOND"), analyzer.dispatches.values().iterator().next().cases().stream().map(Analyzer.DispatchCase::key).toList());
        source("TestPacket", body.replace("return TestPacket.Kind.SECOND", "return TestPacket.Kind.FIRST"));
        assertFalse(new Analyzer(temporary.resolve("sources")).analyze().get(2).supported());
    }
    @Test void optionalGetterProjectionFollowsInterfacesAndRequiresAConcreteDirectAccessor() throws Exception {
        fixture();
        String body = """
            import net.minecraft.resources.Identifier;
            class TestPacket implements Packet<Listener> {
                static final Object STREAM_CODEC = Packet.codec(TestPacket::write, TestPacket::new);
                final Optional<TestPacket.Texture> background;
                TestPacket(FriendlyByteBuf in) {
                    this.background = in.readBoolean() ? Optional.of(new TestPacket.Texture(in.readIdentifier())) : Optional.empty();
                }
                void write(FriendlyByteBuf out) {
                    out.writeBoolean(this.background.isPresent());
                    this.background.map(TestPacket.Asset::id).ifPresent(out::writeIdentifier);
                }
                interface Asset { Identifier id(); }
                record Texture(Identifier id) implements TestPacket.Asset {
                    static final Object STREAM_CODEC = Identifier.STREAM_CODEC.map(TestPacket.Texture::new, TestPacket.Texture::id);
                }
            }
            """;
        source("TestPacket", body);
        var entry = new Analyzer(temporary.resolve("sources")).analyze().get(2);
        assertTrue(entry.supported(), entry.reason());
        source("TestPacket", body.replace("implements TestPacket.Asset", ""));
        assertFalse(new Analyzer(temporary.resolve("sources")).analyze().get(2).supported());
        source("TestPacket", body.replace("record Texture(Identifier id) implements TestPacket.Asset {", "record Texture(Identifier id) implements TestPacket.Asset { public Identifier id() { return transform(this.id); }"));
        assertFalse(new Analyzer(temporary.resolve("sources")).analyze().get(2).supported());
    }
    @Test void retainsWireIdsAcrossBundleAndUnsupportedPacketsAndCompilesUsableCode() throws Exception {
        fixture();
        Analyzer analyzer = new Analyzer(temporary.resolve("sources"));
        var registrations = analyzer.analyze();
        assertEquals(List.of(0, 1, 2), registrations.stream().map(Analyzer.Registration::id).toList());
        assertEquals(List.of(true, false, true), registrations.stream().map(Analyzer.Registration::supported).toList());
        assertTrue(registrations.get(1).reason().contains("control flow"));
        assertEquals("minecraft:test", registrations.get(2).name());
        var report = new Emitter().emit(temporary.resolve("generated"), Map.of("version", "fixture"), 777, registrations, analyzer.models);
        assertEquals(2, report.generatedRegistrations());
        var compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = temporary.resolve("classes"); Files.createDirectories(classes);
        String classpath = String.join(java.io.File.pathSeparator, List.of(Packet.class, ByteBuf.class, io.netty.util.ReferenceCounted.class).stream().map(c -> Path.of(java.net.URI.create(c.getProtectionDomain().getCodeSource().getLocation().toString())).toString()).toList());
        try (var manager = compiler.getStandardFileManager(null, null, null); var files = Files.walk(temporary.resolve("generated/java"))) {
            var inputs = manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList());
            assertTrue(compiler.getTask(null, manager, null, List.of("-classpath", classpath, "-d", classes.toString()), null, inputs).call());
        }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            PacketRegistry registry = (PacketRegistry) loader.loadClass("org.stackanvil.packetforge.protocol.GeneratedProtocol").getMethod("registry").invoke(null);
            ByteBuf data = Unpooled.buffer();
            try {
                data.writeByte(2); WireCodecs.STRING.encode(data, "hello"); WireCodecs.VAR_INT.encode(data, -1);
                Packet packet = registry.decode(PacketRegistry.State.PLAY, PacketRegistry.Flow.CLIENTBOUND, data);
                assertEquals("hello", packet.getClass().getMethod("text").invoke(packet));
                assertEquals(-1, packet.getClass().getMethod("number").invoke(packet));
            } finally { data.release(); }
        }
    }
    @Test void rejectsReaderWriterMismatchAndPreservesExplicitNarrowingTransforms() throws Exception {
        fixture();
        source("TestPacket", """
            record TestPacket(int value) implements Packet<Listener> {
                static final StreamCodec<FriendlyByteBuf, TestPacket> STREAM_CODEC = Packet.codec(TestPacket::write, TestPacket::new);
                TestPacket(FriendlyByteBuf in) { this(in.readVarInt()); }
                void write(FriendlyByteBuf out) { out.writeInt(this.value); }
            }
            """);
        var entries = new Analyzer(temporary.resolve("sources")).analyze();
        assertFalse(entries.get(2).supported());
        assertTrue(entries.get(2).reason().contains("wire types differ"));
        source("TestPacket", """
            record TestPacket(byte value) implements Packet<Listener> {
                static final StreamCodec<FriendlyByteBuf, TestPacket> STREAM_CODEC = Packet.codec(TestPacket::write, TestPacket::new);
                TestPacket(FriendlyByteBuf in) { this((byte) in.readVarInt()); }
                void write(FriendlyByteBuf out) { out.writeVarInt(this.value); }
            }
            """);
        Analyzer narrowed = new Analyzer(temporary.resolve("sources")); var registrations = narrowed.analyze();
        assertTrue(registrations.get(2).supported(), registrations.get(2).reason());
        Path generated = temporary.resolve("narrowed");
        new Emitter().emit(generated, Map.of("version", "fixture"), 777, registrations, narrowed.models);
        var compiler = ToolProvider.getSystemJavaCompiler(); Path classes = temporary.resolve("narrowed-classes"); Files.createDirectories(classes);
        String classpath = String.join(java.io.File.pathSeparator, List.of(Packet.class, ByteBuf.class, io.netty.util.ReferenceCounted.class).stream().map(c -> Path.of(java.net.URI.create(c.getProtectionDomain().getCodeSource().getLocation().toString())).toString()).toList());
        try (var manager = compiler.getStandardFileManager(null, null, null); var files = Files.walk(generated.resolve("java"))) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-classpath", classpath, "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList())).call());
        }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass(registrations.get(2).generatedClass());
            @SuppressWarnings("unchecked") PacketCodec<Object> codec = (PacketCodec<Object>) type.getField("CODEC").get(null);
            ByteBuf input = Unpooled.buffer(), output = Unpooled.buffer();
            try {
                WireCodecs.VAR_INT.encode(input, 255); Object packet = codec.decode(input);
                assertEquals((byte) -1, type.getMethod("value").invoke(packet)); assertFalse(input.isReadable());
                codec.encode(output, packet); assertEquals(-1, WireCodecs.VAR_INT.decode(output)); assertFalse(output.isReadable());
            } finally { input.release(); output.release(); }
        }
    }
}
