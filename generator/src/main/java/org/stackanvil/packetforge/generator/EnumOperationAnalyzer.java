package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Recovers enum-held constructor decoders paired with an operation's virtual writer. */
final class EnumOperationAnalyzer {
    private final Analyzer analyzer;

    EnumOperationAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    List<Field> lower(Expression codec, SourceIndex.Source source) {
        if (!(codec instanceof MethodCallExpr call)
                || call.getArguments().size() != 2
                || !(call.getArgument(0) instanceof MethodReferenceExpr writer)
                || !(call.getArgument(1) instanceof MethodReferenceExpr reader)
                || !reader.getIdentifier().equals("new")
                || !analyzer.index
                        .resolve(reader.getScope().toString(), source)
                        .equals(source.name())
                || !analyzer.index
                        .resolve(writer.getScope().toString(), source)
                        .equals(source.name()))
            throw new Unsupported("Not an enum operation codec");
        var constructors =
                source.declaration().getMembers().stream()
                        .filter(
                                m ->
                                        m instanceof ConstructorDeclaration c
                                                && c.getParameters().size() == 1
                                                && c.getParameter(0)
                                                        .getTypeAsString()
                                                        .endsWith("ByteBuf"))
                        .map(m -> (ConstructorDeclaration) m)
                        .toList();
        var writers = source.declaration().getMethodsByName(writer.getIdentifier());
        if (constructors.size() != 1
                || writers.size() != 1
                || writers.getFirst().isStatic()
                || writers.getFirst().getParameters().size() != 1
                || writers.getFirst().getBody().isEmpty())
            throw new Unsupported("Ambiguous enum operation entry points");
        var constructor = constructors.getFirst();
        var write = writers.getFirst();
        String input = constructor.getParameter(0).getNameAsString(),
                output = write.getParameter(0).getNameAsString();
        var statements = constructor.getBody().getStatements();
        var encoded = write.getBody().get().getStatements();
        int prefix = statements.size() - 2;
        if (prefix < 0
                || encoded.size() != prefix + 2
                || !(statements.get(prefix) instanceof ExpressionStmt declaration)
                || !(declaration.getExpression() instanceof VariableDeclarationExpr variables)
                || variables.getVariables().size() != 1)
            throw new Unsupported("Unknown enum operation reader structure");
        var variable = variables.getVariable(0);
        var keyRead =
                variable.getInitializer()
                        .orElseThrow(() -> new Unsupported("Missing operation discriminator"));
        if (!(keyRead instanceof MethodCallExpr keyCall)
                || !keyCall.getNameAsString().equals("readEnum")
                || !keyCall.getScope().map(Object::toString).orElse("").equals(input)
                || keyCall.getArguments().size() != 1
                || !(keyCall.getArgument(0) instanceof ClassExpr keyClass))
            throw new Unsupported("Operation discriminator is not an ordinal enum");
        String enumName = analyzer.index.resolve(keyClass.getTypeAsString(), source);
        var enumSource = analyzer.index.source(enumName);
        if (!(enumSource.declaration() instanceof EnumDeclaration enumeration))
            throw new Unsupported("Operation discriminator is not an enum");
        if (!(statements.get(statements.size() - 1) instanceof ExpressionStmt assignment)
                || !(assignment.getExpression() instanceof AssignExpr assigned)
                || assigned.getOperator() != AssignExpr.Operator.ASSIGN
                || !(assigned.getTarget() instanceof FieldAccessExpr target)
                || !(target.getScope() instanceof ThisExpr)
                || !(assigned.getValue() instanceof MethodCallExpr decoded)
                || !decoded.getNameAsString().equals("decode")
                || !decoded.getArguments().toString().equals("[" + input + "]")
                || !(decoded.getScope().orElse(null) instanceof FieldAccessExpr decoder)
                || !decoder.getScope().toString().equals(variable.getNameAsString()))
            throw new Unsupported("Unknown enum decoder invocation");
        String field = target.getNameAsString();
        if (!(encoded.get(prefix) instanceof ExpressionStmt keyStatement)
                || !(keyStatement.getExpression() instanceof MethodCallExpr keyWrite)
                || !keyWrite.getNameAsString().equals("writeEnum")
                || !keyWrite.getScope().map(Object::toString).orElse("").equals(output)
                || keyWrite.getArguments().size() != 1
                || !(keyWrite.getArgument(0) instanceof MethodCallExpr getter)
                || !getter.getScope().map(Object::toString).orElse("").equals("this." + field)
                || !getter.getArguments().isEmpty())
            throw new Unsupported("Unknown enum operation discriminator writer");
        if (!(encoded.get(encoded.size() - 1) instanceof ExpressionStmt payloadStatement)
                || !(payloadStatement.getExpression() instanceof MethodCallExpr payloadWrite)
                || !payloadWrite.getScope().map(Object::toString).orElse("").equals("this." + field)
                || !payloadWrite.getArguments().toString().equals("[" + output + "]"))
            throw new Unsupported("Unknown virtual operation writer");
        var enumConstructors = enumeration.getConstructors();
        if (enumConstructors.size() != 1
                || enumConstructors.getFirst().getParameters().size() != 1
                || enumConstructors.getFirst().getBody().getStatements().size() != 1
                || !enumConstructors
                        .getFirst()
                        .getBody()
                        .getStatement(0)
                        .toString()
                        .equals(
                                "this."
                                        + decoder.getNameAsString()
                                        + " = "
                                        + enumConstructors
                                                .getFirst()
                                                .getParameter(0)
                                                .getNameAsString()
                                        + ";"))
            throw new Unsupported("Enum transforms its decoder constructor argument");
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            if (!(statements.get(i) instanceof ExpressionStmt entry)
                    || !(entry.getExpression() instanceof AssignExpr a)
                    || !(a.getTarget() instanceof FieldAccessExpr f)
                    || !(f.getScope() instanceof ThisExpr)
                    || a.getOperator() != AssignExpr.Operator.ASSIGN
                    || !(encoded.get(i) instanceof ExpressionStmt e)
                    || !(e.getExpression() instanceof MethodCallExpr operation))
                throw new Unsupported("Unknown operation prefix layout");
            Wire read = analyzer.read(a.getValue(), input, source);
            Wire written = analyzer.write(operation, output, f.getNameAsString(), read, source);
            if (!read.type().equals(written.type()) || !read.shape().equals(written.shape()))
                throw new Unsupported("Operation prefix wire mismatch");
            fields.add(
                    new Field(
                            f.getNameAsString(),
                            Analyzer.declaredType(f.getNameAsString(), source),
                            read,
                            written));
        }
        List<DispatchCase> cases = new ArrayList<>();
        for (var constant : enumeration.getEntries()) {
            if (constant.getArguments().size() != 1)
                throw new Unsupported("Unknown operation decoder arguments");
            Expression argument = constant.getArgument(0);
            Wire payload;
            if (argument instanceof MethodReferenceExpr reference
                    && reference.getIdentifier().equals("new")) {
                String owner = analyzer.index.resolve(reference.getScope().toString(), enumSource);
                var operation = analyzer.index.source(owner);
                verifyTypeGetter(
                        operation.declaration(),
                        getter.getNameAsString(),
                        enumName,
                        constant.getNameAsString(),
                        operation);
                var model = analyzer.model(owner);
                payload =
                        new Wire(
                                model.generatedName(),
                                model.generatedName() + ".CODEC",
                                "model:" + owner);
                if (!payloadWrite.getNameAsString().equals("write"))
                    throw new Unsupported("Operation model has a different writer");
            } else if (argument instanceof LambdaExpr lambda
                    && lambda.getParameters().size() == 1
                    && lambda.getBody() instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof FieldAccessExpr singleton) {
                var owner =
                        analyzer.index.source(
                                analyzer.index.resolve(
                                        singleton.getScope().toString(), enumSource));
                Expression value =
                        owner.declaration()
                                .getFieldByName(singleton.getNameAsString())
                                .orElseThrow(() -> new Unsupported("Missing operation singleton"))
                                .getVariable(0)
                                .getInitializer()
                                .orElseThrow();
                if (!(value instanceof ObjectCreationExpr anonymous)
                        || anonymous.getAnonymousClassBody().isEmpty())
                    throw new Unsupported("Operation singleton is not an anonymous value");
                var members = anonymous.getAnonymousClassBody().get();
                var getters =
                        members.stream()
                                .filter(
                                        m ->
                                                m instanceof MethodDeclaration d
                                                        && d.getNameAsString()
                                                                .equals(getter.getNameAsString()))
                                .map(m -> (MethodDeclaration) m)
                                .toList();
                if (getters.size() != 1) throw new Unsupported("Missing singleton operation type");
                verifyReturn(getters.getFirst(), enumName, constant.getNameAsString(), owner);
                var methods =
                        members.stream()
                                .filter(
                                        m ->
                                                m instanceof MethodDeclaration d
                                                        && d.getNameAsString()
                                                                .equals(
                                                                        payloadWrite
                                                                                .getNameAsString()))
                                .map(m -> (MethodDeclaration) m)
                                .toList();
                if (methods.size() != 1
                        || methods.getFirst().getParameters().size() != 1
                        || methods.getFirst().getBody().isEmpty()
                        || !methods.getFirst().getBody().get().isEmpty())
                    throw new Unsupported("Singleton operation has a payload");
                payload =
                        new Wire(
                                "org.stackanvil.packetforge.commons.Unit",
                                "WireCodecs.UNIT",
                                "UNIT");
            } else throw new Unsupported("Unknown enum operation constructor decoder");
            cases.add(new DispatchCase(constant.getNameAsString(), payload));
        }
        Wire key = analyzer.read(keyRead, input, source);
        String name = "dispatch_" + Integer.toUnsignedString((enumName + "#operations").hashCode());
        String entries =
                String.join(
                        ", ",
                        cases.stream()
                                .map(
                                        c ->
                                                "java.util.Map.entry("
                                                        + key.type()
                                                        + "."
                                                        + c.key()
                                                        + ", (PacketCodec<?>) "
                                                        + c.value().codec()
                                                        + ")")
                                .toList());
        Wire implementation =
                new Wire(
                        "org.stackanvil.packetforge.commons.Variant<" + key.type() + ">",
                        "WireCodecs.dispatch("
                                + key.codec()
                                + ", java.util.Map.<"
                                + key.type()
                                + ", PacketCodec<?>>ofEntries("
                                + entries
                                + "))",
                        "dispatch:" + name);
        analyzer.dispatches.put(
                name,
                new Dispatch(
                        name,
                        key,
                        List.copyOf(cases),
                        enumName,
                        "enum-decoder:"
                                + decoder.getNameAsString()
                                + ":"
                                + payloadWrite.getNameAsString(),
                        implementation,
                        "",
                        ""));
        Wire wire =
                new Wire(
                        implementation.type(),
                        "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name,
                        implementation.shape());
        fields.add(new Field(field, Analyzer.declaredType(field, source), wire, wire));
        return List.copyOf(fields);
    }

    private void verifyTypeGetter(
            TypeDeclaration<?> declaration,
            String getter,
            String enumName,
            String constant,
            SourceIndex.Source source) {
        var methods = declaration.getMethodsByName(getter);
        if (methods.size() != 1) throw new Unsupported("Missing operation type getter");
        verifyReturn(methods.getFirst(), enumName, constant, source);
    }

    private void verifyReturn(
            MethodDeclaration method, String enumName, String constant, SourceIndex.Source source) {
        if (!method.getParameters().isEmpty()
                || method.getBody().isEmpty()
                || method.getBody().get().getStatements().size() != 1
                || !(method.getBody().get().getStatement(0) instanceof ReturnStmt returned)
                || !(returned.getExpression().orElse(null) instanceof FieldAccessExpr value)
                || !value.getNameAsString().equals(constant)
                || !analyzer.index.resolve(value.getScope().toString(), source).equals(enumName))
            throw new Unsupported("Operation type getter differs from its decoder enum entry");
    }
}
