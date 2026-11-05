package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelExtractorTest {
    @TempDir
    Path temporary;

    private ModelLayout extract(String name, String body, String codec) throws Exception {
        Path file = temporary.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
        var analyzer = new Analyzer(temporary);
        return new ModelExtractor(analyzer)
                .extract(
                        StaticJavaParser.parseExpression(codec),
                        analyzer.index.source(name),
                        analyzer.index.generated(name));
    }

    @Test
    void directFieldLayoutDoesNotNeedAnImperativeProgram() throws Exception {
        var layout = extract("net.minecraft.fixture.Value", """
                record Value(int count) {
                    Value(FriendlyByteBuf input) { this(input.readVarInt()); }
                    void write(FriendlyByteBuf output) { output.writeVarInt(this.count); }
                }
                """, "Packet.codec(Value::write, Value::new)");
        assertEquals(
                List.of("count"),
                layout.fields().stream().map(Analyzer.Field::name).toList());
        assertEquals("VAR_INT", layout.fields().getFirst().read().shape());
        assertNull(layout.program());
    }

    @Test
    void imperativeFallbackRetainsBranchesThatTheDirectRuleCannotLower() throws Exception {
        var layout = extract("net.minecraft.fixture.Flags", """
                class Flags {
                    boolean enabled;
                    Flags(FriendlyByteBuf input) {
                        int flags = input.readByte();
                        this.enabled = (flags & 2) != 0;
                    }
                    void write(FriendlyByteBuf output) { output.writeByte(this.enabled ? 2 : 0); }
                }
                """, "Packet.codec(Flags::write, Flags::new)");
        assertEquals(
                List.of("enabled"),
                layout.fields().stream().map(Analyzer.Field::name).toList());
        assertNotNull(layout.program());
        assertEquals(List.of("BYTE"), layout.program().operations());
    }

    @Test
    void specializedFailureIsTerminalEvenWhenAGeneralRuleWouldAcceptTheCodec() {
        var failure = assertThrows(
                SourceIndex.Unsupported.class,
                () -> extract(
                        "net.minecraft.network.protocol.game.ClientboundCommandsPacket",
                        "class ClientboundCommandsPacket {}",
                        "StreamCodec.unit(new ClientboundCommandsPacket())"));
        assertEquals("Unknown command packet composite layout", failure.getMessage());
    }

    @Test
    void unmatchedImperativeShapeKeepsTheOriginalDiagnostic() {
        var failure = assertThrows(
                SourceIndex.Unsupported.class, () -> extract("net.minecraft.fixture.Value", "class Value {}", "42"));
        assertEquals("Unsupported codec declaration: 42", failure.getMessage());
        assertFalse(ProgramLowerer.accepts(StaticJavaParser.parseExpression("42")));
    }

    @Test
    void rejectedReadWriteLayoutsKeepTheUsefulFallbackDiagnostics() {
        var failure = assertThrows(
                SourceIndex.Unsupported.class,
                () -> extract("net.minecraft.fixture.Value", """
                        record Value(int count) {
                            Value(FriendlyByteBuf input) { this(input.readVarInt()); }
                            void write(FriendlyByteBuf output) { output.writeInt(this.count); }
                        }
                        """, "Packet.codec(Value::write, Value::new)"));
        assertTrue(failure.getMessage().contains("Reader/writer wire types differ for count"));
        assertTrue(failure.getMessage().contains("; imperative:"));
        assertTrue(failure.getMessage().contains("; projection:"));
    }
}
