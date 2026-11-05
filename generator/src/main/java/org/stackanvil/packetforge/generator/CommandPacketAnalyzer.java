package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.stackanvil.packetforge.generator.Analyzer.Field;
import org.stackanvil.packetforge.generator.Analyzer.Wire;
import org.stackanvil.packetforge.generator.AstPattern.Bindings;

/** Recognizes command node flags, registry payloads, and the two graph validation passes. */
final class CommandPacketAnalyzer {
    record Lowered(List<Field> fields, List<String> validations) {}

    private static final AstPattern PACKET_CODEC = AstPattern.expression("""
            StreamCodec.composite(
                $entryCodec.apply(ByteBufCodecs.list()), $first -> $first.entries,
                ByteBufCodecs.VAR_INT, $second -> $second.rootIndex, $Packet::new)
            """);
    private static final AstPattern ENTRY_CODEC = AstPattern.expression("""
            StreamCodec.of(($output, $value) -> $value.write($output), $Packet::$reader)
            """);
    private static final AstPattern NODE_READER = AstPattern.statement("""
            {
                byte $flags = $input.readByte();
                int[] $children = $input.readVarIntArray();
                int $redirect = ($flags & 8) != 0 ? $input.readVarInt() : 0;
                $Stub $stub = $readStub($input, $flags);
                return new $Entry($stub, $flags, $redirect, $children);
            }
            """);
    private static final AstPattern NODE_WRITER = AstPattern.statement("""
            {
                $output.writeByte(this.flags);
                $output.writeVarIntArray(this.children);
                if ((this.flags & 8) != 0) $output.writeVarInt(this.redirect);
                if (this.stub != null) this.stub.write($output);
            }
            """);
    private static final AstPattern STUB_READER = AstPattern.statement("""
            {
                int $type = $flags & 3;
                if ($type == 2) {
                    String $name = $input.readUtf();
                    int $id = $input.readVarInt();
                    $Info $info = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.byId($id);
                    if ($info == null) return null;
                    $Template $argument = $info.deserializeFromNetwork($input);
                    $Identifier $suggestion = ($flags & 16) != 0 ? $input.readIdentifier() : null;
                    return new $Argument($name, $argument, $suggestion);
                } else if ($type == 1) {
                    String $literal = $input.readUtf();
                    return new $Literal($literal);
                } else return null;
            }
            """);
    private static final AstPattern ARGUMENT_WRITER = AstPattern.statement("""
            {
                $output.writeUtf(this.id);
                $serialize($output, this.argumentType);
                if (this.suggestionId != null) $output.writeIdentifier(this.suggestionId);
            }
            """);
    private static final AstPattern SELECT_SERIALIZER = AstPattern.statement("""
            { $serialize($output, $value.type(), $value); }
            """);
    private static final AstPattern WRITE_SERIALIZER = AstPattern.statement("""
            {
                $output.writeVarInt(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId($info));
                $info.serializeToNetwork((T) $value, $output);
            }
            """);
    private static final AstPattern LITERAL_WRITER = AstPattern.statement("""
            { $output.writeUtf(this.id); }
            """);
    private static final AstPattern PACKET_CONSTRUCTOR = AstPattern.statement("""
            {
                this.entries = $entries;
                this.rootIndex = $root;
                $validate(this.entries);
            }
            """);
    private static final AstPattern VALIDATION_PASSES = AstPattern.statement("""
            {
                $validate($entries, $Entry::$build);
                $validate($entries, $Entry::$resolve);
            }
            """);
    private static final AstPattern REDIRECT_DEPENDENCIES = AstPattern.statement("""
            { return (this.flags & 8) != 0 ? !$pending.contains(this.redirect) : true; }
            """);
    private static final AstPattern CHILD_DEPENDENCIES = AstPattern.statement("""
            {
                for (int $child : this.children) if ($pending.contains($child)) return false;
                return true;
            }
            """);
    private static final AstPattern GRAPH_ELIMINATION = AstPattern.statement("""
            {
                $Set $pending = new IntOpenHashSet(IntSets.fromTo(0, $entries.size()));
                while (!$pending.isEmpty()) {
                    boolean $worked = $pending.removeIf($index -> $predicate.test($entries.get($index), $pending));
                    if (!$worked) throw new IllegalStateException("Server sent an impossible command tree");
                }
            }
            """);

    private final Analyzer analyzer;

    CommandPacketAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    Lowered lower(Expression codec, SourceIndex.Source source) {
        var entry = inspect(codec, source);
        return layout(entry, new CommandArgumentAnalyzer(analyzer).table());
    }

    SourceIndex.Source inspect(Expression codec, SourceIndex.Source source) {
        var packet = PACKET_CODEC.require(codec, AstPattern.bindings(), "Unknown command packet composite layout");
        requireOwner(packet, "Packet", source, source.name());
        var initializer = source.declaration()
                .getFieldByName(packet.name("entryCodec"))
                .orElseThrow(() -> new Unsupported("Missing command entry codec"))
                .getVariable(0)
                .getInitializer()
                .orElseThrow(() -> new Unsupported("Uninitialized command entry codec"));
        var pair = ENTRY_CODEC.require(initializer, AstPattern.bindings(), "Unknown command entry codec");
        requireOwner(pair, "Packet", source, source.name());
        var reader = method(source, pair.name("reader"), 1);
        var header = NODE_READER.require(
                reader.getBody().orElseThrow(),
                parameters(reader, "input"),
                "Unknown command node header or flag layout");
        var entry = capturedSource(header, "Entry", source);
        requireWireRecord(entry, List.of("stub", "flags", "redirect", "children"));
        var writer = method(entry, "write", 1);
        NODE_WRITER.require(
                writer.getBody().orElseThrow(),
                parameters(writer, "output"),
                "Command node writer differs from its reader");
        verifyStubs(source, header.name("readStub"));
        verifyValidation(source, entry);
        return entry;
    }

    private Lowered layout(SourceIndex.Source entry, Wire table) {
        String name = table.shape().substring("dispatch:".length());
        Wire entries = new Wire(
                "java.util.List<org.stackanvil.packetforge.commons.CommandNode>",
                "WireCodecs.list(CommandTreeCodecs.node(org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name
                        + "_payloads()), Integer.MAX_VALUE)",
                "command-nodes<" + table.shape() + ">");
        Wire root = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT");
        return new Lowered(
                List.of(
                        new Field("entries", "List<" + entry.name() + ">", entries, entries),
                        new Field("rootIndex", "int", root, root)),
                List.of("CommandTreeCodecs.validate(entries);"));
    }

    private void verifyStubs(SourceIndex.Source source, String helper) {
        var reader = method(source, helper, 2);
        var stubs = STUB_READER.require(
                reader.getBody().orElseThrow(),
                parameters(reader, "input", "flags"),
                "Unknown command argument payload or suggestion layout");
        var argument = capturedSource(stubs, "Argument", source);
        requireWireRecord(argument, List.of("id", "argumentType", "suggestionId"));
        var writer = method(argument, "write", 1);
        var written = ARGUMENT_WRITER.require(
                writer.getBody().orElseThrow(), parameters(writer, "output"), "Unknown command argument writer");
        verifyArgumentSerializer(argument, written.name("serialize"));
        var literal = capturedSource(stubs, "Literal", source);
        requireWireRecord(literal, List.of("id"));
        var literalWriter = method(literal, "write", 1);
        LITERAL_WRITER.require(
                literalWriter.getBody().orElseThrow(),
                parameters(literalWriter, "output"),
                "Literal node writer has additional operations");
    }

    private void verifyArgumentSerializer(SourceIndex.Source source, String helper) {
        var selector = method(source, helper, 2);
        SELECT_SERIALIZER.require(
                selector.getBody().orElseThrow(),
                parameters(selector, "output", "value").withName("serialize", helper),
                "Argument serializer selection transforms its type");
        var network = method(source, helper, 3);
        WRITE_SERIALIZER.require(
                network.getBody().orElseThrow(),
                parameters(network, "output", "info", "value"),
                "Unknown command argument network writer");
    }

    private void verifyValidation(SourceIndex.Source source, SourceIndex.Source entry) {
        var constructors = source.declaration().getMembers().stream()
                .filter(member -> member instanceof ConstructorDeclaration constructor
                        && constructor.getParameters().size() == 2
                        && constructor.getParameter(0).getType().isClassOrInterfaceType()
                        && constructor
                                .getParameter(0)
                                .getType()
                                .asClassOrInterfaceType()
                                .getNameAsString()
                                .equals("List"))
                .map(member -> (ConstructorDeclaration) member)
                .toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous command packet constructor");
        var constructor = constructors.getFirst();
        var construct = PACKET_CONSTRUCTOR.require(
                constructor.getBody(),
                AstPattern.bindings()
                        .withName("entries", constructor.getParameter(0).getNameAsString())
                        .withName("root", constructor.getParameter(1).getNameAsString()),
                "Command packet constructor has additional validation or transforms");
        String validation = construct.name("validate");
        var passes = method(source, validation, 1);
        var validators = VALIDATION_PASSES.require(
                passes.getBody().orElseThrow(),
                parameters(passes, "entries").withName("validate", validation),
                "Unknown command graph validation passes");
        requireOwner(validators, "Entry", source, entry.name());
        var build = method(entry, validators.name("build"), 1);
        REDIRECT_DEPENDENCIES.require(
                build.getBody().orElseThrow(), parameters(build, "pending"), "Unknown command redirect dependencies");
        var resolve = method(entry, validators.name("resolve"), 1);
        CHILD_DEPENDENCIES.require(
                resolve.getBody().orElseThrow(), parameters(resolve, "pending"), "Unknown command child dependencies");
        var graph = method(source, validation, 2);
        GRAPH_ELIMINATION.require(
                graph.getBody().orElseThrow(),
                parameters(graph, "entries", "predicate"),
                "Unknown command graph elimination algorithm");
    }

    private Bindings parameters(MethodDeclaration method, String... roles) {
        var bindings = AstPattern.bindings();
        for (int i = 0; i < roles.length; i++)
            bindings = bindings.withName(roles[i], method.getParameter(i).getNameAsString());
        return bindings;
    }

    private void requireWireRecord(SourceIndex.Source source, List<String> fields) {
        if (!(source.declaration() instanceof RecordDeclaration record)
                || !record.getParameters().stream()
                        .map(parameter -> parameter.getNameAsString())
                        .toList()
                        .equals(fields)
                || record.getMembers().stream()
                        .anyMatch(member -> member instanceof CompactConstructorDeclaration
                                || member instanceof ConstructorDeclaration constructor
                                        && constructor.getParameters().size() == fields.size())) {
            throw new Unsupported("Command wire record has a different layout or constructor: " + source.name());
        }
    }

    private SourceIndex.Source capturedSource(Bindings bindings, String role, SourceIndex.Source owner) {
        String name = bindings.type(role).asClassOrInterfaceType().getNameWithScope();
        return analyzer.index.source(analyzer.index.resolve(name, owner));
    }

    private void requireOwner(Bindings bindings, String role, SourceIndex.Source source, String expected) {
        if (!capturedSource(bindings, role, source).name().equals(expected)) {
            throw new Unsupported("Command " + role + " reference belongs to another type");
        }
    }

    private MethodDeclaration method(SourceIndex.Source source, String name, int parameters) {
        var methods = source.declaration().getMethodsByName(name).stream()
                .filter(method -> method.getParameters().size() == parameters
                        && method.getBody().isPresent())
                .toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous command packet helper: " + name);
        return methods.getFirst();
    }
}
