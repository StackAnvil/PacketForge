package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Retains the bounded custom data that vanilla's login query readers discard. */
final class LoginQueryAnalyzer {
    private final Analyzer analyzer;
    LoginQueryAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    List<Field> lower(Expression codec, SourceIndex.Source source) {
        boolean answer = source.name().equals("net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket");
        if (!answer && !source.name().equals("net.minecraft.network.protocol.login.ClientboundCustomQueryPacket")) throw new Unsupported("Not a login query packet");
        if (!(codec instanceof MethodCallExpr call) || !call.getNameAsString().equals("codec") || call.getArguments().size() != 2 || !(call.getArgument(0) instanceof MethodReferenceExpr writer) || !(call.getArgument(1) instanceof MethodReferenceExpr reader) || !analyzer.index.resolve(writer.getScope().toString(), source).equals(source.name()) || !analyzer.index.resolve(reader.getScope().toString(), source).equals(source.name())) throw new Unsupported("Unknown login query codec entry points");
        var write = method(source, writer.getIdentifier()); String output = write.getParameter(0).getNameAsString();
        var encoded = write.getBody().orElseThrow(() -> new Unsupported("Missing query writer body")).getStatements();
        if (encoded.size() != (answer ? 2 : 3) || !encoded.get(0).toString().equals(output + ".writeVarInt(this.transactionId);")) throw new Unsupported("Unknown login query writer header");
        if (answer) {
            var last = expression(encoded.get(1));
            if (!(last instanceof MethodCallExpr nullable) || !nullable.getNameAsString().equals("writeNullable") || !nullable.getScope().map(Object::toString).orElse("").equals(output) || nullable.getArguments().size() != 2 || !nullable.getArgument(0).toString().equals("this.payload") || !(nullable.getArgument(1) instanceof LambdaExpr lambda) || lambda.getParameters().size() != 2 || !(lambda.getBody() instanceof ExpressionStmt body) || !body.getExpression().toString().equals(lambda.getParameter(1).getNameAsString() + ".write(" + lambda.getParameter(0).getNameAsString() + ")")) throw new Unsupported("Unknown nullable query answer writer");
        } else if (!encoded.get(1).toString().equals(output + ".writeIdentifier(this.payload.id());") || !encoded.get(2).toString().equals("this.payload.write(" + output + ");")) throw new Unsupported("Unknown query channel/payload writer");
        MethodCallExpr payloadRead; String input;
        if (answer) {
            if (reader.getIdentifier().equals("new")) throw new Unsupported("Query answer reader is not a static factory");
            var read = method(source, reader.getIdentifier()); input = read.getParameter(0).getNameAsString(); var body = read.getBody().orElseThrow().getStatements();
            if (!read.isStatic() || body.size() != 2 || !(expression(body.get(0)) instanceof VariableDeclarationExpr variables) || variables.getVariables().size() != 1 || !variables.getVariable(0).getInitializer().map(Object::toString).orElse("").equals(input + ".readVarInt()") || !(body.get(1) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof ObjectCreationExpr created) || !analyzer.index.resolve(created.getTypeAsString(), source).equals(source.name()) || created.getArguments().size() != 2 || !created.getArgument(0).toString().equals(variables.getVariable(0).getNameAsString()) || !(created.getArgument(1) instanceof MethodCallExpr payload)) throw new Unsupported("Unknown query answer reader header");
            if (!payload.getArguments().stream().map(Object::toString).toList().equals(List.of(variables.getVariable(0).getNameAsString(), input))) throw new Unsupported("Query answer transforms its payload reader inputs"); payloadRead = payload;
        } else {
            var constructors = source.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration c && c.getParameters().size() == 1 && c.getParameter(0).getTypeAsString().equals("FriendlyByteBuf")).map(m -> (ConstructorDeclaration) m).toList();
            if (!reader.getIdentifier().equals("new") || constructors.size() != 1) throw new Unsupported("Ambiguous query packet buffer constructor");
            var constructor = constructors.getFirst(); input = constructor.getParameter(0).getNameAsString(); var body = constructor.getBody().getStatements();
            if (body.size() != 1 || !(body.get(0) instanceof ExplicitConstructorInvocationStmt delegated) || !delegated.isThis() || delegated.getArguments().size() != 2 || !delegated.getArgument(0).toString().equals(input + ".readVarInt()") || !(delegated.getArgument(1) instanceof MethodCallExpr payload) || !payload.getArguments().stream().map(Object::toString).toList().equals(List.of(input + ".readIdentifier()", input))) throw new Unsupported("Unknown query packet reader header"); payloadRead = payload;
        }
        if (payloadRead.getScope().isPresent()) throw new Unsupported("Query payload reader is not a local helper");
        var forwarding = method(source, payloadRead.getNameAsString(), 2); var forwarded = returned(forwarding);
        if (!forwarding.isStatic() || !(forwarded instanceof MethodCallExpr unknown) || unknown.getScope().isPresent() || !unknown.getArguments().stream().map(Object::toString).toList().equals(answer ? List.of(forwarding.getParameter(1).getNameAsString()) : forwarding.getParameters().stream().map(p -> p.getNameAsString()).toList())) throw new Unsupported("Query payload reader has an additional layout");
        var fallback = method(source, unknown.getNameAsString(), answer ? 1 : 2); String buffer = fallback.getParameter(answer ? 0 : 1).getNameAsString(); var statements = fallback.getBody().orElseThrow().getStatements();
        if (!fallback.isStatic() || statements.size() != 2 || !(expression(statements.get(0)) instanceof VariableDeclarationExpr variables) || variables.getVariables().size() != 1 || !variables.getVariable(0).getInitializer().map(Object::toString).orElse("").equals(buffer + ".readableBytes()") || !(statements.get(1) instanceof IfStmt guard)) throw new Unsupported("Query fallback is not a bounded remaining-byte reader");
        String length = variables.getVariable(0).getNameAsString();
        if (!(guard.getCondition() instanceof BinaryExpr and) || and.getOperator() != BinaryExpr.Operator.AND || !and.getLeft().toString().equals(length + " >= 0") || !(and.getRight() instanceof BinaryExpr upper) || upper.getOperator() != BinaryExpr.Operator.LESS_EQUALS || !upper.getLeft().toString().equals(length)) throw new Unsupported("Unknown query payload size bound");
        int limit = Integer.parseInt(analyzer.integer(upper.getRight(), source)); if (limit < 0) throw new Unsupported("Negative query payload limit");
        if (!(guard.getThenStmt() instanceof BlockStmt accepted) || accepted.getStatements().size() != 2 || !accepted.getStatement(0).toString().equals(buffer + ".skipBytes(" + length + ");") || !(accepted.getStatement(1) instanceof ReturnStmt discarded)) throw new Unsupported("Query fallback does not discard exactly its remaining bytes");
        Statement rejected = guard.getElseStmt().orElseThrow(() -> new Unsupported("Query fallback has no size rejection"));
        if (rejected instanceof BlockStmt block && block.getStatements().size() == 1) rejected = block.getStatement(0);
        if (!(rejected instanceof ThrowStmt thrown) || !(thrown.getExpression() instanceof ObjectCreationExpr failure) || !failure.getTypeAsString().equals("IllegalArgumentException")) throw new Unsupported("Query fallback does not reject oversized data");
        String discardedName;
        if (answer && discarded.getExpression().orElse(null) instanceof FieldAccessExpr singleton && singleton.getNameAsString().equals("INSTANCE")) discardedName = analyzer.index.resolve(singleton.getScope().toString(), source);
        else if (!answer && discarded.getExpression().orElse(null) instanceof ObjectCreationExpr value && value.getArguments().size() == 1 && value.getArgument(0).toString().equals(fallback.getParameter(0).getNameAsString())) discardedName = analyzer.index.resolve(value.getTypeAsString(), source);
        else throw new Unsupported("Unknown discarded query value");
        var discardedSource = analyzer.index.source(discardedName); var discardedWriter = method(discardedSource, "write");
        if (!(discardedSource.declaration() instanceof RecordDeclaration record) || record.getParameters().size() != (answer ? 0 : 1) || discardedWriter.getBody().isEmpty() || !discardedWriter.getBody().get().isEmpty() || !record.getConstructors().isEmpty() || record.getMembers().stream().anyMatch(m -> m instanceof CompactConstructorDeclaration) || !answer && (!record.getParameter(0).getNameAsString().equals("id") || !record.getMethodsByName("id").isEmpty())) throw new Unsupported("Discarded query value has additional serialized behavior");
        if (answer) {
            var singleton = discardedSource.declaration().getFieldByName("INSTANCE").flatMap(f -> f.getVariable(0).getInitializer()).orElse(null);
            if (!(singleton instanceof ObjectCreationExpr value) || !value.getArguments().isEmpty() || !analyzer.index.resolve(value.getTypeAsString(), discardedSource).equals(discardedName)) throw new Unsupported("Query answer singleton transforms its value");
        }
        Wire transaction = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT"); List<Field> fields = new ArrayList<>(); fields.add(new Field("transactionId", "int", transaction, transaction));
        if (!answer) { Wire channel = new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER", source.name() + "#payload.id"); fields.add(new Field("channel", "Identifier", channel, channel)); }
        String projection = source.name() + "#discarded-query-data" + (answer ? ":includes-nullable-presence-byte" : "");
        fields.add(new Field("data", "byte[]", new Wire("byte[]", "WireCodecs.remainingBytes(" + limit + ")", "remainingBytes(" + limit + ")", projection), new Wire("byte[]", "WireCodecs.remainingBytes(Integer.MAX_VALUE)", "remainingBytes(2147483647)", projection)));
        return List.copyOf(fields);
    }
    private MethodDeclaration method(SourceIndex.Source source, String name) { return method(source, name, 1); }
    private MethodDeclaration method(SourceIndex.Source source, String name, int parameters) {
        var methods = source.declaration().getMethodsByName(name).stream().filter(m -> m.getParameters().size() == parameters).toList();
        if (methods.size() != 1 || methods.getFirst().getBody().isEmpty()) throw new Unsupported("Ambiguous query helper: " + name); return methods.getFirst();
    }
    private Expression returned(MethodDeclaration method) {
        var body = method.getBody().orElseThrow().getStatements();
        if (body.size() != 1 || !(body.get(0) instanceof ReturnStmt returned)) throw new Unsupported("Query forwarding helper has extra operations"); return returned.getExpression().orElseThrow(() -> new Unsupported("Empty query helper result"));
    }
    private Expression expression(Statement statement) { if (!(statement instanceof ExpressionStmt expression)) throw new Unsupported("Unknown query statement"); return expression.getExpression(); }
}
