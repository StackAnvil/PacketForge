package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandPacketAnalyzerTest {
    @TempDir
    Path temporary;

    private static final String SOURCE = """
            package net.minecraft.fixture;
            class TreePacket {
                static final Object STREAM_CODEC = StreamCodec.composite(
                    ENTRIES.apply(ByteBufCodecs.list()), packet -> packet.entries,
                    ByteBufCodecs.VAR_INT, packet -> packet.rootIndex, TreePacket::new);
                static final Object ENTRIES = StreamCodec.of((target, item) -> item.write(target), TreePacket::decodeNode);
                List<TreePacket.Entry> entries;
                int rootIndex;
                TreePacket(List<TreePacket.Entry> nodes, int root) {
                    this.entries = nodes; this.rootIndex = root; validateGraph(this.entries);
                }
                static TreePacket.Entry decodeNode(FriendlyByteBuf buffer) {
                    final byte mask = (buffer.readByte());
                    int[] links = buffer.readVarIntArray();
                    int redirectTo = (mask & 0x08) != 0 ? buffer.readVarInt() : 0;
                    TreePacket.Stub content = decodeContent(buffer, mask);
                    return new TreePacket.Entry(content, mask, redirectTo, links);
                }
                static TreePacket.Stub decodeContent(FriendlyByteBuf buffer, byte mask) {
                    int kind = mask & 3;
                    if (kind == 2) {
                        String label = buffer.readUtf(); int parserId = buffer.readVarInt();
                        ArgumentTypeInfo<?, ?> parser = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.byId(parserId);
                        if (parser == null) return null;
                        ArgumentTypeInfo.Template<?> template = parser.deserializeFromNetwork(buffer);
                        Identifier suggestion = (mask & 16) != 0 ? buffer.readIdentifier() : null;
                        return new TreePacket.Argument(label, template, suggestion);
                    } else if (kind == 1) {
                        String text = buffer.readUtf(); return new TreePacket.Literal(text);
                    } else return null;
                }
                record Entry(TreePacket.Stub stub, int flags, int redirect, int[] children) {
                    void write(FriendlyByteBuf target) {
                        target.writeByte(this.flags); target.writeVarIntArray(this.children);
                        if ((this.flags & 8) != 0) target.writeVarInt(this.redirect);
                        if (this.stub != null) this.stub.write(target);
                    }
                    boolean isBuildable(IntSet remaining) { return (this.flags & 8) != 0 ? !remaining.contains(this.redirect) : true; }
                    boolean isResolvable(IntSet remaining) {
                        for (int next : this.children) { if (remaining.contains(next)) { return false; } }
                        return true;
                    }
                }
                interface Stub {}
                record Argument(String id, Object argumentType, Identifier suggestionId) implements TreePacket.Stub {
                    void write(FriendlyByteBuf target) {
                        target.writeUtf(this.id); encodeParser(target, this.argumentType);
                        if (this.suggestionId != null) target.writeIdentifier(this.suggestionId);
                    }
                    static void encodeParser(FriendlyByteBuf target, Object template) { encodeParser(target, template.type(), template); }
                    static void encodeParser(FriendlyByteBuf target, Object parser, Object template) {
                        target.writeVarInt(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId(parser));
                        parser.serializeToNetwork((T) template, target);
                    }
                }
                record Literal(String id) implements TreePacket.Stub {
                    void write(FriendlyByteBuf target) { target.writeUtf(this.id); }
                }
                static void validateGraph(List<TreePacket.Entry> tree) {
                    validateGraph(tree, TreePacket.Entry::isBuildable); validateGraph(tree, TreePacket.Entry::isResolvable);
                }
                static void validateGraph(List<TreePacket.Entry> tree, BiPredicate predicate) {
                    IntSet remaining = new IntOpenHashSet(IntSets.fromTo(0, tree.size()));
                    while (!remaining.isEmpty()) {
                        boolean progress = remaining.removeIf(x -> predicate.test(tree.get(x), remaining));
                        if (!progress) throw new IllegalStateException("Server sent an impossible command tree");
                    }
                }
            }
            """;

    private SourceIndex.Source inspect(String body) throws Exception {
        Path file = temporary.resolve("net/minecraft/fixture/TreePacket.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        var analyzer = new Analyzer(temporary);
        var source = analyzer.index.source("net.minecraft.fixture.TreePacket");
        var codec = source.declaration()
                .getFieldByName("STREAM_CODEC")
                .orElseThrow()
                .getVariable(0)
                .getInitializer()
                .orElseThrow();
        return new CommandPacketAnalyzer(analyzer).inspect(codec, source);
    }

    @Test
    void followsRolesAcrossRenamedLocalsAndPrivateHelpers() throws Exception {
        assertEquals("net.minecraft.fixture.TreePacket.Entry", inspect(SOURCE).name());
        String renamed = SOURCE.replaceAll("\\bmask\\b", "bits")
                .replaceAll("\\bbuffer\\b", "bytes")
                .replaceAll("\\bremaining\\b", "pending")
                .replaceAll("\\bprogress\\b", "changed")
                .replaceAll("\\bdecodeNode\\b", "readEntry")
                .replaceAll("\\bdecodeContent\\b", "readValue")
                .replaceAll("\\bvalidateGraph\\b", "checkDependencies")
                .replaceAll("\\bencodeParser\\b", "writeType");
        assertEquals("net.minecraft.fixture.TreePacket.Entry", inspect(renamed).name());
    }

    @Test
    void extraParenthesesCommentsAndBranchBracesDoNotChangeTheLayout() throws Exception {
        String equivalent = SOURCE.replace("mask & 0x08", "((mask) & (8))")
                .replace("buffer.readVarIntArray()", "(buffer.readVarIntArray())")
                .replace(
                        "if (parser == null) return null;",
                        "if ((parser) == (null)) { /* No known parser. */ return null; }");
        assertEquals(
                "net.minecraft.fixture.TreePacket.Entry", inspect(equivalent).name());
    }

    @Test
    void changedWireOperationsAndHiddenIoRemainUnsupported() {
        for (String body : List.of(
                SOURCE.replace("buffer.readByte()", "buffer.readUnsignedByte()"),
                SOURCE.replace("mask & 0x08", "mask & 4"),
                SOURCE.replace("decodeContent(buffer, mask)", "decodeContent(buffer, (byte) 0)"),
                SOURCE.replace(
                        "int[] links = buffer.readVarIntArray();",
                        "int[] links = buffer.readVarIntArray(); buffer.readByte();"),
                SOURCE.replace("parser.deserializeFromNetwork(buffer)", "parser.otherCodec(buffer)"),
                SOURCE.replace("content, mask, redirectTo, links", "content, mask, 0, links"),
                SOURCE.replace("target.writeByte(this.flags)", "target.writeByte(this.flags | 4)"),
                SOURCE.replace("target.writeUtf(this.id)", "target.writeIdentifier(this.id)"))) {
            assertThrows(SourceIndex.Unsupported.class, () -> inspect(body));
        }
    }

    @Test
    void changedGraphDependenciesOrValidationPassesRemainUnsupported() {
        for (String body : List.of(
                SOURCE.replace("remaining.contains(this.redirect)", "remaining.contains(0)"),
                SOURCE.replace("tree.get(x)", "tree.get(x + 1)"),
                SOURCE.replace("remaining.removeIf", "remaining.remove"),
                SOURCE.replace("TreePacket.Entry::isBuildable", "TreePacket.Entry::isResolvable"),
                SOURCE.replace("IntSets.fromTo(0, tree.size())", "IntSets.fromTo(1, tree.size())"),
                SOURCE.replace("if (!progress) throw", "if (progress) throw"))) {
            assertThrows(SourceIndex.Unsupported.class, () -> inspect(body));
        }
    }

    @Test
    void recordFieldOrderAndConstructorBehaviorCannotBeSilentlyProjectedAway() {
        for (String body : List.of(
                SOURCE.replace("Stub stub, int flags, int redirect", "Stub stub, int redirect, int flags"),
                SOURCE.replace(
                        "record Literal(String id) implements TreePacket.Stub {",
                        "record Literal(String id) implements TreePacket.Stub { Literal { if (id.isEmpty()) throw new IllegalArgumentException(); }"))) {
            var failure = assertThrows(SourceIndex.Unsupported.class, () -> inspect(body));
            assertTrue(failure.getMessage().startsWith("Command wire record has a different layout or constructor:"));
        }
    }
}
