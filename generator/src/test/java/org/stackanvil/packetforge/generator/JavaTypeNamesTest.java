package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaTypeNamesTest {
    @TempDir
    Path directory;

    @Test
    void renamedSymbolsCompileWhileIdenticalTextInsideStringsStaysUnchanged() throws Exception {
        var names = new JavaTypeNames(Map.of("fixture.Old_Record", "fixture.NewRecord", "Old_Record", "NewRecord"));
        String method = names.member("""
            public static int run() {
                java.util.function.IntFunction<fixture.Old_Record> factory = fixture.Old_Record::new;
                fixture.Old_Record value = factory.apply(5);
                Class<fixture.Old_Record> token = fixture.Old_Record.class;
                if (token != value.getClass()) throw new AssertionError();
                return fixture.Old_Record.BASE + value.value();
            }
            """);
        String literal =
                names.member("public static String original() { return \"fixture.Old_Record Old_Record.BASE\"; }");
        String constructor = names.member("public static Old_Record build() { return new Old_Record(3); }");
        Path model = directory.resolve("NewRecord.java");
        Path access = directory.resolve("Access.java");
        Files.writeString(
                model, "package fixture; public record NewRecord(int value) { public static final int BASE = 7; }");
        Files.writeString(access, "package fixture; public class Access { " + method + literal + constructor + " }");
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(
                            null,
                            manager,
                            null,
                            List.of("-d", directory.toString()),
                            null,
                            manager.getJavaFileObjectsFromPaths(List.of(model, access)))
                    .call());
        }
        try (var loader = new URLClassLoader(
                new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("fixture.Access");
            assertEquals(12, type.getMethod("run").invoke(null));
            assertEquals(
                    "fixture.Old_Record Old_Record.BASE",
                    type.getMethod("original").invoke(null));
            assertEquals(
                    "fixture.NewRecord",
                    type.getMethod("build").invoke(null).getClass().getName());
        }
    }
}
