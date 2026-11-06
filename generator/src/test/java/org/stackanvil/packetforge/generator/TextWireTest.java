package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextWireTest {
    @TempDir
    Path directory;

    @Test
    void recognizesTextAndStylesThroughResolvedCodecIdentitiesAndPreservesTrust() throws Exception {
        source(
                "net.minecraft.network.chat.ComponentSerialization",
                "class ComponentSerialization { static Object CODEC; }");
        source("net.minecraft.network.chat.Style", "class Style { static class Serializer { static Object CODEC; } }");
        source("fixture.Uses", """
                import net.minecraft.network.chat.ComponentSerialization;
                import net.minecraft.network.chat.Style;
                class Uses {}
                """);
        var analyzer = new Analyzer(directory);
        var owner = analyzer.index.source("fixture.Uses");
        var component = analyzer.codec(
                StaticJavaParser.parseExpression(
                        "ByteBufCodecs.fromCodecWithRegistriesTrusted((ComponentSerialization.CODEC))"),
                owner);
        assertEquals("net.kyori.adventure.text.Component", component.type());
        assertTrue(component.codec().endsWith("TextCodecs.TRUSTED_COMPONENT"));
        var style = analyzer.codec(
                StaticJavaParser.parseExpression("ByteBufCodecs.fromCodec(Style.Serializer.CODEC)"), owner);
        assertEquals("net.kyori.adventure.text.format.Style", style.type());
        assertTrue(style.codec().endsWith("TextCodecs.STYLE"));
        var local = analyzer.codec(
                StaticJavaParser.parseExpression("ByteBufCodecs.fromCodec(CODEC)"),
                analyzer.index.source("net.minecraft.network.chat.ComponentSerialization"));
        assertEquals(TextWire.component(false), local);
    }

    @Test
    void keepsLookalikePersistentCodecsAsGenericNbt() throws Exception {
        source("fixture.ComponentSerialization", "class ComponentSerialization { static Object CODEC; }");
        var analyzer = new Analyzer(directory);
        var wire = analyzer.codec(
                StaticJavaParser.parseExpression("ByteBufCodecs.fromCodec(CODEC)"),
                analyzer.index.source("fixture.ComponentSerialization"));
        assertEquals("org.stackanvil.packetforge.commons.NbtTag", wire.type());
        assertEquals("NbtCodecs.TAG", wire.codec());
    }

    private void source(String name, String body) throws Exception {
        Path file = directory.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }
}
