package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionSetAnalyzerTest {
    @TempDir
    Path temporary;

    private static final String SOURCE = """
        package net.minecraft.fixture;
        class Batch {
            EnumSet<Batch.Action> actions;
            List<Batch.Entry> entries;
            Batch(RegistryFriendlyByteBuf input) {
                this.actions = input.readEnumSet(Batch.Action.class);
                this.entries = entryCodec(this.actions).apply(ByteBufCodecs.list()).decode(input);
            }
            void write(RegistryFriendlyByteBuf output) {
                output.writeEnumSet(this.actions, Batch.Action.class);
                entryCodec(this.actions).apply(ByteBufCodecs.list()).encode(output, this.entries);
            }
            static Object entryCodec(EnumSet<Batch.Action> actions) {
                return StreamCodec.of((output, entry) -> {
                    output.writeUUID(entry.profileId());
                    for (Batch.Action action : actions) { action.writer.write(output, entry); }
                }, input -> {
                    Batch.Builder builder = new Batch.Builder(input.readUUID());
                    for (Batch.Action action : actions) { action.reader.read(builder, input); }
                    return builder.build();
                });
            }
            enum Action {
                NUMBER(0, (entry, input) -> entry.number = input.readVarInt(), (output, entry) -> output.writeVarInt(entry.number())),
                LISTED(1, (entry, input) -> entry.listed = input.readBoolean(), (output, entry) -> output.writeBoolean(entry.listed()));
                int id;
                Reader reader;
                Writer writer;
                Action(int id, Reader reader, Writer writer) { this.id = id; this.reader = reader; this.writer = writer; }
            }
            record Entry(UUID profileId, int number, boolean listed) {}
            static class Builder {
                UUID profileId;
                int number;
                boolean listed;
                Builder(UUID profileId) { this.profileId = profileId; }
                Batch.Entry build() { return new Batch.Entry(this.profileId, this.number, this.listed); }
            }
        }
        """;

    private ProgramLowerer.Lowered analyze(String body) throws Exception {
        Path file = temporary.resolve("net/minecraft/fixture/Batch.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        var analyzer = new Analyzer(temporary);
        var source = analyzer.index.source("net.minecraft.fixture.Batch");
        return new ActionSetAnalyzer(analyzer)
                .lower(
                        StaticJavaParser.parseExpression("Packet.codec(Batch::write, Batch::new)"),
                        source,
                        analyzer.index.generated(source.name()));
    }

    @Test
    void recoversTheSharedMaskAndDependentEntryPayloads() throws Exception {
        var lowered = analyze(SOURCE);
        assertEquals(
                List.of("actions", "entries"),
                lowered.fields().stream().map(Analyzer.Field::name).toList());
        assertEquals(
                "enum-mask:net.minecraft.fixture.Batch.Action",
                lowered.fields().getFirst().read().shape());
        assertTrue(lowered.fields().get(1).read().type().contains("ActionEntry<"));
    }

    @Test
    void rejectsUnmatchedActionsWireWidthsAndBuilderTransforms() {
        for (String source : List.of(
                SOURCE.replace("LISTED(1,", "LISTED(3,"),
                SOURCE.replace("output.writeVarInt(entry.number())", "output.writeInt(entry.number())"),
                SOURCE.replace(
                        "this.profileId, this.number, this.listed", "this.profileId, this.number + 1, this.listed"),
                SOURCE.replace("this.reader = reader", "this.reader = decorate(reader)"),
                SOURCE.replace("action : actions", "action : actions.reversed()"),
                SOURCE.replace("this.actions, Batch.Action.class", "this.actions, Other.class"))) {
            assertThrows(SourceIndex.Unsupported.class, () -> analyze(source));
        }
    }
}
