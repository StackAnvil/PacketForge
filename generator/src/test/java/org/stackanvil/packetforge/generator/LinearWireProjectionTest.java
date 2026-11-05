package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinearWireProjectionTest {
    @TempDir
    Path temporary;

    private List<Analyzer.Field> project(String reader, String writer) throws Exception {
        Path file = temporary.resolve("example/Value.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package example;
            class Value {
                Value(FriendlyByteBuf input) { %s }
                void write(FriendlyByteBuf output) { %s }
            }
            """.formatted(reader, writer));
        var analyzer = new Analyzer(temporary);
        return new LinearWireProjection(analyzer)
                .lower(
                        StaticJavaParser.parseExpression("Packet.codec(Value::write, Value::new)"),
                        analyzer.index.source("example.Value"));
    }

    @Test
    void retainsPhysicalFieldsAndBothDirectionsOfObjectProjections() throws Exception {
        var fields = project(
                "int direction = Direction.fromId(input.readUnsignedByte()); int count = input.readVarInt(); normalize(direction, count);",
                "output.writeByte(this.direction.getId()); output.writeVarInt(this.normalizedCount);");
        assertEquals(
                List.of("direction", "count"),
                fields.stream().map(Analyzer.Field::name).toList());
        assertEquals(
                List.of("UNSIGNED_BYTE", "VAR_INT"),
                fields.stream().map(f -> f.read().shape()).toList());
        assertTrue(fields.getFirst().read().projection().contains("Direction.fromId"));
        assertTrue(fields.getFirst().write().projection().contains("direction.getId"));
    }

    @Test
    void refusesConditionalReadsHiddenIoAndDifferentWriteLayouts() {
        for (String reader : List.of(
                "int value = flag ? input.readVarInt() : 0;",
                "boolean value = flag && input.readBoolean();",
                "int value = input.readVarInt(); normalize(); input.readByte();",
                "int value = merge(input.readVarInt(), input.readVarInt());")) {
            assertThrows(
                    SourceIndex.Unsupported.class, () -> project(reader, "output.writeVarInt(this.value);"), reader);
        }
        assertThrows(
                SourceIndex.Unsupported.class,
                () -> project("int value = input.readVarInt();", "output.writeInt(this.value);"));
        assertThrows(
                SourceIndex.Unsupported.class,
                () -> project(
                        "int value = input.readVarInt();", "output.writeVarInt(this.value); output.writeByte(0);"));
        assertThrows(
                SourceIndex.Unsupported.class,
                () -> project("int value = input.readVarInt();", "if (flag) output.writeVarInt(this.value);"));
    }
}
