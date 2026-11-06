package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

class StatusWireTest {
    @TempDir
    Path directory;

    private static final String STATUS = """
        import com.mojang.serialization.Codec;
        import com.mojang.serialization.DataResult;
        import com.mojang.serialization.codecs.RecordCodecBuilder;
        import java.util.List;
        import java.util.Optional;
        import java.util.Base64;
        import java.nio.charset.StandardCharsets;
        import net.minecraft.network.chat.Component;
        import net.minecraft.network.chat.ComponentSerialization;
        import net.minecraft.network.chat.CommonComponents;
        import net.minecraft.server.players.NameAndId;
        record ServerStatus(Component description, Optional<Players> players, Optional<Version> version, Optional<Favicon> favicon, boolean enforcesSecureChat) {
            static Object CODEC = RecordCodecBuilder.create(builder -> builder.group(
                ComponentSerialization.CODEC.lenientOptionalFieldOf("description", CommonComponents.EMPTY).forGetter(ServerStatus::description),
                Players.CODEC.lenientOptionalFieldOf("players").forGetter(ServerStatus::players),
                Version.CODEC.lenientOptionalFieldOf("version").forGetter(ServerStatus::version),
                Favicon.CODEC.lenientOptionalFieldOf("favicon").forGetter(ServerStatus::favicon),
                Codec.BOOL.lenientOptionalFieldOf("enforcesSecureChat", false).forGetter(ServerStatus::enforcesSecureChat)
            ).apply(builder, ServerStatus::new));
            record Players(int max, int online, List<NameAndId> sample) {
                static Object CODEC = RecordCodecBuilder.create(b -> b.group(
                    Codec.INT.fieldOf("max").forGetter(Players::max),
                    Codec.INT.fieldOf("online").forGetter(Players::online),
                    NameAndId.CODEC.listOf().lenientOptionalFieldOf("sample", List.of()).forGetter(Players::sample)
                ).apply(b, Players::new));
            }
            record Version(String name, int protocol) {
                static Object CODEC = RecordCodecBuilder.create(b -> b.group(
                    Codec.STRING.fieldOf("name").forGetter(Version::name),
                    Codec.INT.fieldOf("protocol").forGetter(Version::protocol)
                ).apply(b, Version::new));
            }
            record Favicon(byte[] iconBytes) {
                static Object CODEC = Codec.STRING.comapFlatMap(input -> {
                    if (!input.startsWith("data:image/png;base64,")) return DataResult.error(() -> "Unknown format");
                    try {
                        final String encoded = input.substring("data:image/png;base64,".length()).replaceAll("\\n", "");
                        byte[] decoded = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
                        return DataResult.success(new Favicon(decoded));
                    } catch (IllegalArgumentException invalid) {
                        return DataResult.error(() -> "Malformed base64 server icon");
                    }
                }, icon -> "data:image/png;base64," + new String(Base64.getEncoder().encode(icon.iconBytes), StandardCharsets.UTF_8));
            }
        }
        """;

    @Test
    void recognizesTypedStatusWithoutLoadingGameClasses() throws Exception {
        fixture();
        var wire = lower(STATUS);
        assertEquals("org.stackanvil.packetforge.commons.status.ServerStatus", wire.type());
        assertEquals(
                "org.stackanvil.packetforge.commons.status.StatusCodecs.status(JsonCodecs.json(32767))", wire.codec());
    }

    @Test
    void rejectsChangedDefaultsFieldRulesFaviconLayoutsAndRecordConstruction() throws Exception {
        fixture();
        for (String changed : new String[] {
            STATUS.replace("\"enforcesSecureChat\", false", "\"enforcesSecureChat\", true"),
            STATUS.replace("lenientOptionalFieldOf(\"players\")", "optionalFieldOf(\"players\")"),
            STATUS.replace("Codec.INT.fieldOf(\"online\")", "Codec.LONG.fieldOf(\"online\")"),
            STATUS.replace("image/png", "image/jpeg"),
            STATUS.replace(
                    "static Object CODEC = RecordCodecBuilder.create(builder",
                    "ServerStatus { throw new IllegalArgumentException(); } static Object CODEC = RecordCodecBuilder.create(builder")
        }) assertThrows(Unsupported.class, () -> lower(changed));
    }

    private void fixture() throws Exception {
        source(
                "fixture.Uses",
                "import net.minecraft.network.protocol.status.ServerStatus; import com.mojang.serialization.JsonOps; import net.minecraft.core.RegistryAccess; class Uses { static Object OPS = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE); }");
        source(
                "net.minecraft.network.chat.CommonComponents",
                "import net.minecraft.network.chat.Component; class CommonComponents { static Object EMPTY = Component.empty(); }");
        source("net.minecraft.server.players.NameAndId", """
            import java.util.UUID;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.UUIDUtil;
            record NameAndId(UUID id, String name) {
                static Object CODEC = RecordCodecBuilder.create(b -> b.group(UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(NameAndId::id), Codec.STRING.fieldOf("name").forGetter(NameAndId::name)).apply(b, NameAndId::new));
            }
            """);
    }

    @Test
    void followsTheEmptyRegistryContextUsedByTheSourcePacket() throws Exception {
        fixture();
        assertEquals(
                "org.stackanvil.packetforge.commons.status.ServerStatus",
                lower(STATUS, "OPS").type());
    }

    @Test
    void rejectsCompressedJsonOperations() throws Exception {
        fixture();
        assertThrows(Unsupported.class, () -> lower(STATUS, "JsonOps.COMPRESSED"));
    }

    private Analyzer.Wire lower(String status) throws Exception {
        return lower(status, "JsonOps.INSTANCE");
    }

    private Analyzer.Wire lower(String status, String operations) throws Exception {
        source("net.minecraft.network.protocol.status.ServerStatus", status);
        var analyzer = new Analyzer(directory);
        return analyzer.codec(
                StaticJavaParser.parseExpression("ByteBufCodecs.lenientJson(32767).apply(ByteBufCodecs.fromCodec("
                        + operations + ", ServerStatus.CODEC))"),
                analyzer.index.source("fixture.Uses"));
    }

    private void source(String name, String body) throws Exception {
        var path = directory.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }
}
