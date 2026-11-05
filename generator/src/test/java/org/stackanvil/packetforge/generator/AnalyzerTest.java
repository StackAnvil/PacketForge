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
    @Test void rejectsReaderWriterMismatchAndNumericTransforms() throws Exception {
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
        assertTrue(new Analyzer(temporary.resolve("sources")).analyze().get(2).reason().contains("Numeric cast"));
    }
}
