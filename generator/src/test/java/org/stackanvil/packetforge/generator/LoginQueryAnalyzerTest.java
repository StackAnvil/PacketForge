package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginQueryAnalyzerTest {
    @TempDir Path temporary;
    private static final String QUERY =
            """
        package net.minecraft.network.protocol.login;
        record ClientboundCustomQueryPacket(int transactionId, Payload payload) {
            ClientboundCustomQueryPacket(FriendlyByteBuf buffer) {
                this(buffer.readVarInt(), payload(buffer.readIdentifier(), buffer));
            }
            void write(FriendlyByteBuf target) {
                target.writeVarInt(this.transactionId);
                target.writeIdentifier(this.payload.id());
                this.payload.write(target);
            }
            static Payload payload(Identifier channel, FriendlyByteBuf bytes) { return discard(channel, bytes); }
            static Discarded discard(Identifier channel, FriendlyByteBuf bytes) {
                int count = bytes.readableBytes();
                if (count >= 0 && count <= 7) {
                    bytes.skipBytes(count);
                    return new Discarded(channel);
                } else { throw new IllegalArgumentException("Too much query data"); }
            }
        }
        """;

    private List<Analyzer.Field> analyze(String source, String discarded) throws Exception {
        Path directory = temporary.resolve("net/minecraft/network/protocol/login");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("ClientboundCustomQueryPacket.java"), source);
        Files.writeString(
                directory.resolve("Discarded.java"),
                "package net.minecraft.network.protocol.login; " + discarded);
        var analyzer = new Analyzer(temporary);
        return new LoginQueryAnalyzer(analyzer)
                .lower(
                        StaticJavaParser.parseExpression(
                                "Packet.codec(ClientboundCustomQueryPacket::write, ClientboundCustomQueryPacket::new)"),
                        analyzer.index.source(
                                "net.minecraft.network.protocol.login.ClientboundCustomQueryPacket"));
    }

    private static final String DISCARDED =
            "record Discarded(Identifier id) { void write(FriendlyByteBuf target) {} }";

    @Test
    void extractsBoundedDiscardedDataSeparatelyFromTheTransactionAndChannel() throws Exception {
        var fields = analyze(QUERY, DISCARDED);
        assertEquals(
                List.of("transactionId", "channel", "data"),
                fields.stream().map(Analyzer.Field::name).toList());
        assertEquals("WireCodecs.remainingBytes(7)", fields.get(2).read().codec());
        assertEquals("WireCodecs.remainingBytes(Integer.MAX_VALUE)", fields.get(2).write().codec());
        assertTrue(fields.get(2).read().projection().contains("discarded-query-data"));
    }

    @Test
    void rejectsPartialDiscardingChangedHeadersAndSerializedFallbackValues() {
        for (String source :
                List.of(
                        QUERY.replace("bytes.skipBytes(count)", "bytes.skipBytes(count - 1)"),
                        QUERY.replace("count <= 7", "count < 7"),
                        QUERY.replace("buffer.readVarInt()", "buffer.readInt()"),
                        QUERY.replace(
                                "target.writeIdentifier(this.payload.id())",
                                "target.writeUtf(this.payload.id())"),
                        QUERY.replace(
                                "return discard(channel, bytes)",
                                "return knownOrUnknown(channel, bytes)"))) {
            assertThrows(SourceIndex.Unsupported.class, () -> analyze(source, DISCARDED));
        }
        assertThrows(
                SourceIndex.Unsupported.class,
                () -> analyze(QUERY, DISCARDED.replace("{}", "{ target.writeByte(1); }")));
    }
}
