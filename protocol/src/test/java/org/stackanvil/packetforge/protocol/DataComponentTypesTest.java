package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCounted;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stackanvil.packetforge.commons.ComponentCodecs;
import org.stackanvil.packetforge.commons.ComponentPatch;
import org.stackanvil.packetforge.commons.Unit;

class DataComponentTypesTest {
    @TempDir
    Path classes;

    @Test
    void exposesEveryExtractedComponentWithItsOriginalIdentity() throws Exception {
        try (var reader = new InputStreamReader(
                getClass().getResourceAsStream("/packetforge/protocol.json"), StandardCharsets.UTF_8)) {
            var report = JsonParser.parseReader(reader).getAsJsonObject();
            var table = java.util.stream.StreamSupport.stream(
                            report.getAsJsonArray("dispatches").spliterator(), false)
                    .map(value -> value.getAsJsonObject())
                    .filter(value -> value.get("registry").getAsString().equals("DATA_COMPONENT_TYPE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(122, DataComponentTypes.values().size());
            assertEquals(
                    table.getAsJsonArray("cases").size(),
                    DataComponentTypes.values().size());
            for (var entry : table.getAsJsonArray("cases")) {
                var definition = entry.getAsJsonObject();
                int id = definition.get("key").getAsInt();
                Key key = Key.key(definition.get("name").getAsString());
                var type = DataComponentTypes.byId(id);
                assertEquals(id, type.id());
                assertEquals(key, type.key());
                assertSame(type, DataComponentTypes.byKey(key));
            }
            assertThrows(IllegalArgumentException.class, () -> DataComponentTypes.byId(-1));
            assertThrows(IllegalArgumentException.class, () -> DataComponentTypes.byId(122));
            assertThrows(IllegalArgumentException.class, () -> DataComponentTypes.byKey(Key.key("test:missing")));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> DataComponentTypes.values().clear());
        }
    }

    @Test
    void constructedPatchesKeepTypedValuesAndRemovalSemanticsAcrossTheWire() {
        var patch = ComponentPatch.builder()
                .set(DataComponentTypes.DAMAGE, 12)
                .set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE)
                .remove(DataComponentTypes.CUSTOM_NAME)
                .build();
        for (boolean delimited : new boolean[] {false, true}) {
            var codec = ComponentCodecs.patch(DataComponentTypes.values(), delimited);
            var buffer = Unpooled.buffer();
            try {
                codec.encode(buffer, patch);
                var decoded = codec.decode(buffer);
                assertEquals(12, decoded.get(DataComponentTypes.DAMAGE).orElseThrow());
                assertEquals(
                        Unit.INSTANCE,
                        decoded.get(DataComponentTypes.UNBREAKABLE).orElseThrow());
                assertTrue(decoded.get(DataComponentTypes.CUSTOM_NAME).isEmpty());
                assertTrue(decoded.isRemoved(DataComponentTypes.CUSTOM_NAME));
                assertFalse(buffer.isReadable());
                assertEquals(patch, decoded);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void generatedKeysRejectIncompatibleValuesDuringJavaCompilation() throws Exception {
        String source = """
                import org.stackanvil.packetforge.protocol.DataComponentTypes;
                import org.stackanvil.packetforge.commons.ComponentPatch;
                class Client {
                    ComponentPatch patch = ComponentPatch.builder().set(DataComponentTypes.DAMAGE, 12).build();
                    int damage = patch.get(DataComponentTypes.DAMAGE).orElseThrow();
                }
                """;
        assertTrue(compile(source));
        assertFalse(compile(source.replace("DAMAGE, 12", "DAMAGE, \"wrong\"")));
    }

    private boolean compile(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        String classpath = String.join(
                java.io.File.pathSeparator,
                List.of(
                                DataComponentTypes.class,
                                ComponentPatch.class,
                                Key.class,
                                ByteBuf.class,
                                ReferenceCounted.class)
                        .stream()
                        .map(type -> Path.of(URI.create(type.getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toString()))
                                .toString())
                        .toList());
        var input = new SimpleJavaFileObject(URI.create("string:///Client.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            return compiler.getTask(
                            null,
                            manager,
                            diagnostics,
                            List.of("-classpath", classpath, "-d", classes.toString()),
                            null,
                            List.of(input))
                    .call();
        }
    }
}
