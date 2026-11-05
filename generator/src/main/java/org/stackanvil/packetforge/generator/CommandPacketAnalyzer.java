package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Recognizes command node flags, registry payloads, and the two graph validation passes. */
final class CommandPacketAnalyzer {
    record Lowered(List<Field> fields, List<String> validations) {}

    private final Analyzer analyzer;

    CommandPacketAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    Lowered lower(Expression codec, SourceIndex.Source source) {
        if (!(codec instanceof MethodCallExpr composite)
                || !composite.getNameAsString().equals("composite")
                || composite.getArguments().size() != 5
                || !composite
                        .getArgument(0)
                        .toString()
                        .equals("ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list())")
                || !composite.getArgument(2).toString().equals("ByteBufCodecs.VAR_INT")
                || !composite
                        .getArgument(4)
                        .toString()
                        .equals(source.declaration().getNameAsString() + "::new"))
            throw new Unsupported("Unknown command packet composite layout");
        for (int i : new int[] {1, 3})
            if (!(composite.getArgument(i) instanceof LambdaExpr getter)
                    || getter.getParameters().size() != 1
                    || !(getter.getBody() instanceof ExpressionStmt e)
                    || !e.getExpression()
                            .toString()
                            .equals(
                                    getter.getParameter(0).getNameAsString()
                                            + "."
                                            + (i == 1 ? "entries" : "rootIndex")))
                throw new Unsupported("Unknown command packet getters");
        var entryCodec =
                source.declaration()
                        .getFieldByName("ENTRY_STREAM_CODEC")
                        .orElseThrow()
                        .getVariable(0)
                        .getInitializer()
                        .orElseThrow();
        if (!(entryCodec instanceof MethodCallExpr pair)
                || !pair.getNameAsString().equals("of")
                || pair.getArguments().size() != 2
                || !(pair.getArgument(0) instanceof LambdaExpr writer)
                || writer.getParameters().size() != 2
                || !(writer.getBody() instanceof ExpressionStmt e)
                || !e.getExpression()
                        .toString()
                        .equals(
                                writer.getParameter(1).getNameAsString()
                                        + ".write("
                                        + writer.getParameter(0).getNameAsString()
                                        + ")")
                || !(pair.getArgument(1) instanceof MethodReferenceExpr reader)
                || !reader.getIdentifier().equals("readNode")
                || !analyzer.index
                        .resolve(reader.getScope().toString(), source)
                        .equals(source.name()))
            throw new Unsupported("Unknown command entry codec");
        var read = method(source, "readNode", 1);
        String input = read.getParameter(0).getNameAsString();
        var body = read.getBody().orElseThrow().getStatements();
        if (body.size() != 5
                || !body.get(0).toString().equals("byte flags = " + input + ".readByte();")
                || !body.get(1)
                        .toString()
                        .equals("int[] children = " + input + ".readVarIntArray();")
                || !body.get(2)
                        .toString()
                        .equals("int redirect = (flags & 8) != 0 ? " + input + ".readVarInt() : 0;")
                || !(body.get(3) instanceof ExpressionStmt declaration)
                || !(declaration.getExpression() instanceof VariableDeclarationExpr variables)
                || variables.getVariables().size() != 1
                || !variables
                        .getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals("read(" + input + ", flags)")
                || !(body.get(4) instanceof ReturnStmt returned)
                || !(returned.getExpression().orElse(null) instanceof ObjectCreationExpr entry)
                || !entry.getArguments().stream()
                        .map(Object::toString)
                        .toList()
                        .equals(List.of("stub", "flags", "redirect", "children")))
            throw new Unsupported("Unknown command node header or flag layout");
        var entrySource =
                analyzer.index.source(analyzer.index.resolve(entry.getTypeAsString(), source));
        var encode = method(entrySource, "write", 1);
        String output = encode.getParameter(0).getNameAsString();
        var writes = encode.getBody().orElseThrow().getStatements();
        if (writes.size() != 4
                || !writes.get(0).toString().equals(output + ".writeByte(this.flags);")
                || !writes.get(1).toString().equals(output + ".writeVarIntArray(this.children);")
                || !(writes.get(2) instanceof IfStmt redirect)
                || !redirect.getCondition().toString().equals("(this.flags & 8) != 0")
                || !single(redirect.getThenStmt())
                        .toString()
                        .equals(output + ".writeVarInt(this.redirect);")
                || redirect.getElseStmt().isPresent()
                || !(writes.get(3) instanceof IfStmt stub)
                || !stub.getCondition().toString().equals("this.stub != null")
                || !single(stub.getThenStmt()).toString().equals("this.stub.write(" + output + ");")
                || stub.getElseStmt().isPresent())
            throw new Unsupported("Command node writer differs from its reader");
        verifyStub(source);
        verifyValidation(source, entrySource);
        Wire table = new CommandArgumentAnalyzer(analyzer).table();
        String name = table.shape().substring("dispatch:".length());
        Wire entries =
                new Wire(
                        "java.util.List<org.stackanvil.packetforge.commons.CommandNode>",
                        "WireCodecs.list(CommandTreeCodecs.node(org.stackanvil.packetforge.protocol.GeneratedWireCodecs."
                                + name
                                + "_payloads()), Integer.MAX_VALUE)",
                        "command-nodes<" + table.shape() + ">");
        Wire root = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT");
        return new Lowered(
                List.of(
                        new Field("entries", "List<" + entrySource.name() + ">", entries, entries),
                        new Field("rootIndex", "int", root, root)),
                List.of("CommandTreeCodecs.validate(entries);"));
    }

    private void verifyStub(SourceIndex.Source source) {
        var read = method(source, "read", 2);
        String input = read.getParameter(0).getNameAsString(),
                flags = read.getParameter(1).getNameAsString();
        var body = read.getBody().orElseThrow().getStatements();
        if (body.size() != 2
                || !body.get(0).toString().equals("int type = " + flags + " & 3;")
                || !(body.get(1) instanceof IfStmt argument)
                || !argument.getCondition().toString().equals("type == 2")
                || !(argument.getThenStmt() instanceof BlockStmt args)
                || args.getStatements().size() != 7
                || !(argument.getElseStmt().orElse(null) instanceof IfStmt literal)
                || !literal.getCondition().toString().equals("type == 1"))
            throw new Unsupported("Unknown command stub discriminator");
        if (!args.getStatement(0).toString().equals("String name = " + input + ".readUtf();")
                || !args.getStatement(1).toString().equals("int id = " + input + ".readVarInt();")
                || !(args.getStatement(2) instanceof ExpressionStmt registry)
                || !(registry.getExpression() instanceof VariableDeclarationExpr vars)
                || !vars.getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals("BuiltInRegistries.COMMAND_ARGUMENT_TYPE.byId(id)")
                || !(args.getStatement(3) instanceof IfStmt unknown)
                || !unknown.getCondition().toString().equals("argumentType == null")
                || !single(unknown.getThenStmt()).toString().equals("return null;")
                || unknown.getElseStmt().isPresent())
            throw new Unsupported("Unknown command argument ID lookup or fallback");
        if (!(args.getStatement(4) instanceof ExpressionStmt decode)
                || !(decode.getExpression() instanceof VariableDeclarationExpr decoded)
                || !decoded.getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals("argumentType.deserializeFromNetwork(" + input + ")")
                || !(args.getStatement(5) instanceof ExpressionStmt suggestions)
                || !(suggestions.getExpression() instanceof VariableDeclarationExpr suggested)
                || !suggested
                        .getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals("(" + flags + " & 16) != 0 ? " + input + ".readIdentifier() : null")
                || !(args.getStatement(6) instanceof ReturnStmt returned)
                || !(returned.getExpression().orElse(null) instanceof ObjectCreationExpr created)
                || !created.getArguments().stream()
                        .map(Object::toString)
                        .toList()
                        .equals(List.of("name", "argument", "suggestionId")))
            throw new Unsupported("Unknown command argument payload or suggestion layout");
        var argumentSource =
                analyzer.index.source(analyzer.index.resolve(created.getTypeAsString(), source));
        var writer = method(argumentSource, "write", 1);
        String output = writer.getParameter(0).getNameAsString();
        var writes = writer.getBody().orElseThrow().getStatements();
        if (writes.size() != 3
                || !writes.get(0).toString().equals(output + ".writeUtf(this.id);")
                || !writes.get(1)
                        .toString()
                        .equals("serializeCap(" + output + ", this.argumentType);")
                || !(writes.get(2) instanceof IfStmt suggestion)
                || !suggestion.getCondition().toString().equals("this.suggestionId != null")
                || !single(suggestion.getThenStmt())
                        .toString()
                        .equals(output + ".writeIdentifier(this.suggestionId);")
                || suggestion.getElseStmt().isPresent())
            throw new Unsupported("Unknown command argument writer");
        var cap = method(argumentSource, "serializeCap", 2);
        String out = cap.getParameter(0).getNameAsString(),
                value = cap.getParameter(1).getNameAsString();
        if (!cap.getBody()
                .orElseThrow()
                .getStatements()
                .toString()
                .equals("[serializeCap(" + out + ", " + value + ".type(), " + value + ");]"))
            throw new Unsupported("Argument serializer selection transforms its type");
        var network = method(argumentSource, "serializeCap", 3);
        var operations = network.getBody().orElseThrow().getStatements();
        out = network.getParameter(0).getNameAsString();
        String info = network.getParameter(1).getNameAsString();
        value = network.getParameter(2).getNameAsString();
        if (operations.size() != 2
                || !operations
                        .get(0)
                        .toString()
                        .equals(
                                out
                                        + ".writeVarInt(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId("
                                        + info
                                        + "));")
                || !operations
                        .get(1)
                        .toString()
                        .equals(info + ".serializeToNetwork((T) " + value + ", " + out + ");"))
            throw new Unsupported("Unknown command argument network writer");
        if (!(literal.getThenStmt() instanceof BlockStmt literals)
                || literals.getStatements().size() != 2
                || !literals.getStatement(0)
                        .toString()
                        .equals("String id = " + input + ".readUtf();")
                || !(literals.getStatement(1) instanceof ReturnStmt result)
                || !(result.getExpression().orElse(null) instanceof ObjectCreationExpr payload)
                || !payload.getArguments().toString().equals("[id]")
                || !single(literal.getElseStmt().orElseThrow()).toString().equals("return null;"))
            throw new Unsupported("Unknown literal/root command payload");
        var literalSource =
                analyzer.index.source(analyzer.index.resolve(payload.getTypeAsString(), source));
        var literalWriter = method(literalSource, "write", 1);
        if (!literalWriter
                .getBody()
                .orElseThrow()
                .getStatements()
                .toString()
                .equals(
                        "["
                                + literalWriter.getParameter(0).getNameAsString()
                                + ".writeUtf(this.id);]"))
            throw new Unsupported("Literal node writer has additional operations");
    }

    private void verifyValidation(SourceIndex.Source source, SourceIndex.Source entry) {
        var constructors =
                source.declaration().getMembers().stream()
                        .filter(
                                m ->
                                        m instanceof ConstructorDeclaration c
                                                && c.getParameters().size() == 2
                                                && c.getParameter(0)
                                                        .getTypeAsString()
                                                        .startsWith("List<"))
                        .map(m -> (ConstructorDeclaration) m)
                        .toList();
        if (constructors.size() != 1
                || !constructors.getFirst().getBody().getStatements().stream()
                        .map(Object::toString)
                        .toList()
                        .equals(
                                List.of(
                                        "this.entries = entries;",
                                        "this.rootIndex = rootIndex;",
                                        "validateEntries(this.entries);")))
            throw new Unsupported(
                    "Command packet constructor has additional validation or transforms");
        var validator = method(source, "validateEntries", 1);
        if (!validator.getBody().orElseThrow().getStatements().stream()
                .map(Object::toString)
                .toList()
                .equals(
                        List.of(
                                "validateEntries(entries, "
                                        + source.declaration().getNameAsString()
                                        + ".Entry::canBuild);",
                                "validateEntries(entries, "
                                        + source.declaration().getNameAsString()
                                        + ".Entry::canResolve);")))
            throw new Unsupported("Unknown command graph validation passes");
        var build = method(entry, "canBuild", 1);
        if (!single(build.getBody().orElseThrow())
                .toString()
                .equals(
                        "return (this.flags & 8) != 0 ? !"
                                + build.getParameter(0).getNameAsString()
                                + ".contains(this.redirect) : true;"))
            throw new Unsupported("Unknown command redirect dependencies");
        var resolve = method(entry, "canResolve", 1);
        var body = resolve.getBody().orElseThrow().getStatements();
        if (body.size() != 2
                || !(body.get(0) instanceof ForEachStmt loop)
                || !loop.getIterable().toString().equals("this.children")
                || !(single(loop.getBody()) instanceof IfStmt child)
                || !child.getCondition()
                        .toString()
                        .equals(
                                resolve.getParameter(0).getNameAsString()
                                        + ".contains("
                                        + loop.getVariable().getVariable(0).getNameAsString()
                                        + ")")
                || !single(child.getThenStmt()).toString().equals("return false;")
                || child.getElseStmt().isPresent()
                || !body.get(1).toString().equals("return true;"))
            throw new Unsupported("Unknown command child dependencies");
        var graph = method(source, "validateEntries", 2);
        var statements = graph.getBody().orElseThrow().getStatements();
        if (statements.size() != 2
                || !(statements.get(1) instanceof WhileStmt whileLoop)
                || !whileLoop.getCondition().toString().equals("!elementsToCheck.isEmpty()")
                || !(whileLoop.getBody() instanceof BlockStmt iteration)
                || iteration.getStatements().size() != 2
                || !(iteration.getStatement(0) instanceof ExpressionStmt removed)
                || !(removed.getExpression() instanceof VariableDeclarationExpr values)
                || !values.getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals(
                                "elementsToCheck.removeIf(index -> validator.test(entries.get(index), elementsToCheck))")
                || !(iteration.getStatement(1) instanceof IfStmt failure)
                || !failure.getCondition().toString().equals("!worked")
                || !(single(failure.getThenStmt()) instanceof ThrowStmt))
            throw new Unsupported("Unknown command graph elimination algorithm");
        if (!(statements.get(0) instanceof ExpressionStmt initial)
                || !(initial.getExpression() instanceof VariableDeclarationExpr variables)
                || !variables
                        .getVariable(0)
                        .getInitializer()
                        .map(Object::toString)
                        .orElse("")
                        .equals("new IntOpenHashSet(IntSets.fromTo(0, entries.size()))"))
            throw new Unsupported("Command graph validator starts with a different node set");
    }

    private MethodDeclaration method(SourceIndex.Source source, String name, int parameters) {
        var methods =
                source.declaration().getMethodsByName(name).stream()
                        .filter(
                                m ->
                                        m.getParameters().size() == parameters
                                                && m.getBody().isPresent())
                        .toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous command packet helper: " + name);
        return methods.getFirst();
    }

    private Statement single(Statement statement) {
        if (statement instanceof BlockStmt block && block.getStatements().size() == 1)
            return block.getStatement(0);
        return statement;
    }
}
