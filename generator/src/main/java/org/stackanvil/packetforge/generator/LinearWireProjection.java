package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Projects independent wire reads and writes across game-object construction and normalization. */
final class LinearWireProjection {
    private final Analyzer analyzer;

    LinearWireProjection(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    List<Field> lower(Expression codec, SourceIndex.Source source) {
        BlockStmt read, write;
        String input, output;
        if (codec instanceof ObjectCreationExpr created
                && created.getAnonymousClassBody().isPresent()) {
            var members = created.getAnonymousClassBody().get();
            var reader = method(members, "decode", 1);
            var writer = method(members, "encode", 2);
            read =
                    reader.getBody()
                            .orElseThrow(() -> new Unsupported("Projected reader has no body"));
            write =
                    writer.getBody()
                            .orElseThrow(() -> new Unsupported("Projected writer has no body"));
            input = reader.getParameter(0).getNameAsString();
            output = writer.getParameter(0).getNameAsString();
        } else if (codec instanceof MethodCallExpr call
                && call.getArguments().size() == 2
                && Set.of("of", "codec", "ofMember").contains(call.getNameAsString())
                && call.getArgument(0) instanceof MethodReferenceExpr writer
                && call.getArgument(1) instanceof MethodReferenceExpr reader) {
            var encoders =
                    source.declaration().getMethodsByName(writer.getIdentifier()).stream()
                            .filter(
                                    m ->
                                            m.getParameters().size() >= 1
                                                    && buffer(m.getParameter(0).getTypeAsString()))
                            .toList();
            if (encoders.size() != 1) throw new Unsupported("Ambiguous wire-projection writer");
            var encoder = encoders.getFirst();
            write =
                    encoder.getBody()
                            .orElseThrow(() -> new Unsupported("Projected writer has no body"));
            output = encoder.getParameter(0).getNameAsString();
            if (reader.getIdentifier().equals("new")) {
                var constructors =
                        source.declaration().getMembers().stream()
                                .filter(
                                        m ->
                                                m instanceof ConstructorDeclaration c
                                                        && c.getParameters().size() == 1
                                                        && buffer(
                                                                c.getParameter(0)
                                                                        .getTypeAsString()))
                                .map(m -> (ConstructorDeclaration) m)
                                .toList();
                if (constructors.size() != 1)
                    throw new Unsupported("Ambiguous wire-projection constructor");
                read = constructors.getFirst().getBody();
                input = constructors.getFirst().getParameter(0).getNameAsString();
            } else {
                var readers =
                        source.declaration().getMethodsByName(reader.getIdentifier()).stream()
                                .filter(
                                        m ->
                                                m.getParameters().size() == 1
                                                        && buffer(
                                                                m.getParameter(0)
                                                                        .getTypeAsString()))
                                .toList();
                if (readers.size() != 1) throw new Unsupported("Ambiguous wire-projection reader");
                read =
                        readers.getFirst()
                                .getBody()
                                .orElseThrow(() -> new Unsupported("Projected reader has no body"));
                input = readers.getFirst().getParameter(0).getNameAsString();
            }
        } else throw new Unsupported("Not an independent linear wire projection");
        return lowerBodies(read, write, input, output, source);
    }

    List<Field> lowerBodies(
            BlockStmt read,
            BlockStmt write,
            String input,
            String output,
            SourceIndex.Source source) {
        List<Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean finished = false;
        for (var statement : read.getStatements()) {
            String name = null, originalType = null;
            Expression value = null;
            if (!finished && statement instanceof ExpressionStmt expression) {
                if (expression.getExpression() instanceof VariableDeclarationExpr variables
                        && variables.getVariables().size() == 1
                        && variables.getVariable(0).getInitializer().isPresent()) {
                    var variable = variables.getVariable(0);
                    name = variable.getNameAsString();
                    originalType = variable.getTypeAsString();
                    value = variable.getInitializer().get();
                } else if (expression.getExpression() instanceof AssignExpr assignment
                        && assignment.getOperator() == AssignExpr.Operator.ASSIGN
                        && assignment.getTarget() instanceof FieldAccessExpr target
                        && target.getScope() instanceof ThisExpr) {
                    name = target.getNameAsString();
                    originalType = Analyzer.declaredType(name, source);
                    value = assignment.getValue();
                }
            }
            if (value != null && mentions(value, input)) {
                Wire wire = read(value, input, source);
                if (!names.add(name))
                    throw new Unsupported("Wire projection assigns a field more than once");
                fields.add(
                        new Field(
                                name,
                                originalType,
                                new Wire(
                                        wire.type(),
                                        wire.codec(),
                                        wire.shape(),
                                        source.name() + "#decode:" + value),
                                wire));
            } else {
                finished = true;
                if (mentions(statement, input))
                    throw new Unsupported(
                            "Wire projection reader has dependent or conditional buffer operations");
            }
        }
        if (fields.isEmpty()) throw new Unsupported("Wire projection has no independent reads");
        int position = 0;
        for (var statement : write.getStatements()) {
            if (statement instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof MethodCallExpr call
                    && writes(call, output)) {
                if (position >= fields.size())
                    throw new Unsupported("Wire projection has more writes than reads");
                var field = fields.get(position);
                MethodCallExpr projected = call.clone();
                int argument = call.getNameAsString().equals("encode") ? 1 : 0;
                projected.setArgument(argument, new FieldAccessExpr(new ThisExpr(), field.name()));
                Wire encoded =
                        analyzer.write(projected, output, field.name(), field.read(), source);
                if (!field.read().type().equals(encoded.type())
                        || !field.read().shape().equals(encoded.shape()))
                    throw new Unsupported(
                            "Wire projection reader and writer operations differ at "
                                    + field.name());
                fields.set(
                        position++,
                        new Field(
                                field.name(),
                                field.originalType(),
                                field.read(),
                                new Wire(
                                        encoded.type(),
                                        encoded.codec(),
                                        encoded.shape(),
                                        source.name() + "#encode:" + call.getArgument(argument))));
            } else if (mentions(statement, output))
                throw new Unsupported(
                        "Wire projection writer has dependent or conditional buffer operations");
        }
        if (position != fields.size())
            throw new Unsupported("Wire projection reader and writer operation counts differ");
        return List.copyOf(fields);
    }

    private Wire read(Expression expression, String input, SourceIndex.Source source) {
        try {
            return analyzer.read(expression, input, source);
        } catch (Unsupported transformation) {
        }
        if (!expression.findAll(ConditionalExpr.class).isEmpty()
                || !expression.findAll(SwitchExpr.class).isEmpty()
                || !expression.findAll(LambdaExpr.class).isEmpty()
                || expression.findAll(BinaryExpr.class).stream()
                        .anyMatch(
                                b ->
                                        Set.of(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR)
                                                .contains(b.getOperator())))
            throw new Unsupported("Projected read contains conditional evaluation");
        var values =
                expression.findAll(MethodCallExpr.class).stream()
                        .filter(
                                c ->
                                        c.getScope().map(Object::toString).orElse("").equals(input)
                                                        && c.getNameAsString().startsWith("read")
                                                || c.getNameAsString().equals("decode")
                                                        && c.getArguments().size() == 1
                                                        && c.getArgument(0)
                                                                .toString()
                                                                .equals(input))
                        .toList();
        if (values.size() != 1
                || expression.findAll(NameExpr.class).stream()
                                .filter(n -> n.getNameAsString().equals(input))
                                .count()
                        != 1)
            throw new Unsupported("Projected read does not have one independent wire operation");
        return analyzer.read(values.getFirst(), input, source);
    }

    private boolean writes(MethodCallExpr call, String output) {
        return call.getScope().map(Object::toString).orElse("").equals(output)
                        && call.getNameAsString().startsWith("write")
                        && !call.getArguments().isEmpty()
                || call.getNameAsString().equals("encode")
                        && call.getArguments().size() == 2
                        && call.getArgument(0).toString().equals(output);
    }

    private boolean mentions(com.github.javaparser.ast.Node node, String name) {
        return node.findAll(NameExpr.class).stream()
                .anyMatch(n -> n.getNameAsString().equals(name));
    }

    private MethodDeclaration method(
            List<BodyDeclaration<?>> members, String name, int parameters) {
        var methods =
                members.stream()
                        .filter(
                                m ->
                                        m instanceof MethodDeclaration d
                                                && d.getNameAsString().equals(name)
                                                && d.getParameters().size() == parameters)
                        .map(m -> (MethodDeclaration) m)
                        .toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous projected codec method: " + name);
        return methods.getFirst();
    }

    private boolean buffer(String type) {
        return Set.of("ByteBuf", "FriendlyByteBuf", "RegistryFriendlyByteBuf").contains(type);
    }
}
