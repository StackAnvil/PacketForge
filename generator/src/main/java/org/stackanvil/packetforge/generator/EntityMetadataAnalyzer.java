package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Recovers the explicit serializer registration sequence and terminated data-value lists. */
final class EntityMetadataAnalyzer {
    private final Analyzer analyzer;

    EntityMetadataAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    Wire table() {
        var source = analyzer.index.source("net.minecraft.network.syncher.EntityDataSerializers");
        String name =
                "dispatch_" + Integer.toUnsignedString((source.name() + "#SERIALIZERS").hashCode());
        if (analyzer.dispatches.containsKey(name)) return reference(analyzer.dispatches.get(name));
        List<DispatchCase> cases = new ArrayList<>();
        List<RegistryGap> gaps = new ArrayList<>();
        var resolver = new StaticCodecResolver(analyzer);
        for (var member : source.declaration().getMembers())
            if (member instanceof InitializerDeclaration initializer && initializer.isStatic()) {
                for (var statement : initializer.getBody().getStatements()) {
                    if (!(statement instanceof ExpressionStmt expression)
                            || !(expression.getExpression() instanceof MethodCallExpr call)
                            || call.getScope().isPresent()
                            || !call.getNameAsString().equals("registerSerializer")
                            || call.getArguments().size() != 1
                            || !(call.getArgument(0) instanceof NameExpr field))
                        throw new Unsupported("Unknown entity serializer registration operation");
                    String fieldName = field.getNameAsString();
                    int id = cases.size() + gaps.size();
                    var value =
                            source.declaration()
                                    .getFieldByName(fieldName)
                                    .flatMap(f -> f.getVariable(0).getInitializer())
                                    .orElseThrow(
                                            () ->
                                                    new Unsupported(
                                                            "Missing entity serializer declaration: "
                                                                    + fieldName));
                    try {
                        Wire codec;
                        if (value instanceof MethodCallExpr factory
                                && factory.getScope()
                                        .map(Object::toString)
                                        .orElse("")
                                        .equals("EntityDataSerializer")
                                && factory.getNameAsString().equals("forValueType")
                                && factory.getArguments().size() == 1) {
                            var type =
                                    analyzer.index.source(
                                            analyzer.index.resolve("EntityDataSerializer", source));
                            var methods = type.declaration().getMethodsByName("forValueType");
                            if (methods.size() != 1
                                    || methods.getFirst().getParameters().size() != 1
                                    || methods.getFirst().getBody().isEmpty())
                                throw new Unsupported("Unknown value-type serializer factory");
                            var body = methods.getFirst().getBody().get();
                            if (body.getStatements().size() != 1
                                    || !(body.getStatement(0) instanceof ReturnStmt returned)
                                    || !(returned.getExpression().orElse(null)
                                            instanceof LambdaExpr lambda)
                                    || !lambda.getParameters().isEmpty()
                                    || !(lambda.getBody() instanceof ExpressionStmt payload)
                                    || !payload.getExpression()
                                            .toString()
                                            .equals(
                                                    methods.getFirst()
                                                            .getParameter(0)
                                                            .getNameAsString()))
                                throw new Unsupported(
                                        "Value-type serializer factory changes its payload codec");
                            codec = analyzer.codec(factory.getArgument(0), source);
                        } else
                            codec =
                                    resolver.codec(
                                            resolver.accessor(
                                                    new StaticCodecResolver.Value(value, source),
                                                    "codec"));
                        cases.add(new DispatchCase(Integer.toString(id), codec, fieldName));
                    } catch (Unsupported missing) {
                        gaps.add(new RegistryGap(id, fieldName, missing.getMessage()));
                    }
                }
            }
        if (cases.isEmpty() && gaps.isEmpty())
            throw new Unsupported("No entity serializers were registered");
        if (!gaps.isEmpty()) {
            analyzer.registryGaps.put("ENTITY_DATA_SERIALIZERS", List.copyOf(gaps));
            throw new Unsupported(
                    "Entity serializer table has "
                            + gaps.size()
                            + " unresolved payloads; first: "
                            + gaps.getFirst().reason());
        }
        analyzer.registryGaps.remove("ENTITY_DATA_SERIALIZERS");
        String entries =
                String.join(
                        ", ",
                        cases.stream()
                                .map(
                                        c ->
                                                "java.util.Map.entry("
                                                        + c.key()
                                                        + ", (PacketCodec<?>) "
                                                        + c.value().codec()
                                                        + ")")
                                .toList());
        Wire key = new Wire("int", "WireCodecs.VAR_INT", "ENTITY_DATA_SERIALIZER_ID");
        Wire implementation =
                new Wire(
                        "org.stackanvil.packetforge.commons.Variant<Integer>",
                        "WireCodecs.dispatch(WireCodecs.VAR_INT, java.util.Map.<Integer, PacketCodec<?>>ofEntries("
                                + entries
                                + "))",
                        "dispatch:" + name);
        Dispatch definition =
                new Dispatch(
                        name,
                        key,
                        List.copyOf(cases),
                        source.name(),
                        "registerSerializer",
                        implementation,
                        "ENTITY_DATA_SERIALIZERS",
                        "");
        analyzer.dispatches.put(name, definition);
        return reference(definition);
    }

    private Wire reference(Dispatch definition) {
        return new Wire(
                definition.implementation().type(),
                "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + definition.name(),
                "dispatch:" + definition.name());
    }

    Wire read(MethodCallExpr call, String input, SourceIndex.Source source) {
        var methods = source.declaration().getMethodsByName(call.getNameAsString());
        if (methods.size() != 1
                || methods.getFirst().getParameters().size() != 1
                || methods.getFirst().getBody().isEmpty())
            throw new Unsupported("Not a terminated metadata reader");
        var method = methods.getFirst();
        String buffer = method.getParameter(0).getNameAsString();
        var body = method.getBody().get();
        if (body.getStatements().size() != 4
                || !(body.getStatement(0) instanceof ExpressionStmt declaration)
                || !(declaration.getExpression() instanceof VariableDeclarationExpr variables)
                || variables.getVariables().size() != 1
                || !(variables.getVariable(0).getInitializer().orElse(null)
                        instanceof ObjectCreationExpr created)
                || !created.getType().getNameAsString().equals("ArrayList")
                || !created.getArguments().isEmpty()
                || !(body.getStatement(1) instanceof ExpressionStmt indexDeclaration)
                || !(indexDeclaration.getExpression()
                        instanceof VariableDeclarationExpr indexVariables)
                || indexVariables.getVariables().size() != 1
                || indexVariables.getVariable(0).getInitializer().isPresent()
                || !(body.getStatement(2) instanceof WhileStmt)
                || !(body.getStatement(3) instanceof ReturnStmt returned)
                || !returned.getExpression()
                        .map(Object::toString)
                        .orElse("")
                        .equals(variables.getVariable(0).getNameAsString()))
            throw new Unsupported("Metadata reader has extra control flow or transforms");
        var loops = body.findAll(WhileStmt.class);
        if (loops.size() != 1
                || !(loops.getFirst().getCondition() instanceof BinaryExpr condition)
                || condition.getOperator() != BinaryExpr.Operator.NOT_EQUALS
                || !condition.getRight().toString().equals("255"))
            throw new Unsupported("Not a metadata sentinel loop");
        Expression left = condition.getLeft();
        if (left instanceof EnclosedExpr enclosed) left = enclosed.getInner();
        if (!(left instanceof AssignExpr assignment)
                || assignment.getOperator() != AssignExpr.Operator.ASSIGN
                || !assignment
                        .getTarget()
                        .toString()
                        .equals(indexVariables.getVariable(0).getNameAsString())
                || !assignment.getValue().toString().equals(buffer + ".readUnsignedByte()"))
            throw new Unsupported("Metadata reader has a different index encoding");
        Statement loopBody = loops.getFirst().getBody();
        if (loopBody instanceof BlockStmt block && block.getStatements().size() == 1)
            loopBody = block.getStatement(0);
        if (!(loopBody instanceof ExpressionStmt addition)
                || !(addition.getExpression() instanceof MethodCallExpr add)
                || !add.getNameAsString().equals("add")
                || !add.getScope()
                        .map(Object::toString)
                        .orElse("")
                        .equals(variables.getVariable(0).getNameAsString())
                || add.getArguments().size() != 1)
            throw new Unsupported("Metadata loop changes its list entries");
        var reads =
                loops.getFirst().getBody().findAll(MethodCallExpr.class).stream()
                        .filter(
                                c ->
                                        c.getNameAsString().equals("read")
                                                && c.getArguments().size() == 2
                                                && c.getArgument(0).toString().equals(buffer)
                                                && c.getArgument(1)
                                                        .toString()
                                                        .equals(assignment.getTarget().toString())
                                                && c.getScope().isPresent()
                                                && analyzer.index
                                                        .resolve(
                                                                c.getScope().get().toString(),
                                                                source)
                                                        .equals(
                                                                "net.minecraft.network.syncher.SynchedEntityData.DataValue"))
                        .toList();
        if (reads.size() != 1 || body.findAll(MethodCallExpr.class).size() != 3)
            throw new Unsupported("Metadata reader transforms its entries");
        Wire table = table();
        return new Wire(
                "java.util.List<org.stackanvil.packetforge.commons.MetadataEntry>",
                "MetadataCodecs.entries(" + table.codec() + ")",
                "metadata<" + table.shape() + ">");
    }

    boolean writes(MethodCallExpr call, String output, String field, SourceIndex.Source source) {
        if (call.getArguments().size() != 2
                || !call.getArgument(1).toString().equals(output)
                || !call.getArgument(0).toString().equals("this." + field)) return false;
        var methods = source.declaration().getMethodsByName(call.getNameAsString());
        if (methods.size() != 1
                || methods.getFirst().getParameters().size() != 2
                || methods.getFirst().getBody().isEmpty()) return false;
        var method = methods.getFirst();
        String values = method.getParameter(0).getNameAsString(),
                buffer = method.getParameter(1).getNameAsString();
        var body = method.getBody().get();
        if (body.getStatements().size() != 2
                || !(body.getStatement(0) instanceof ForEachStmt loop)
                || !loop.getIterable().toString().equals(values)
                || !body.getStatement(1).toString().equals(buffer + ".writeByte(255);"))
            return false;
        String item = loop.getVariable().getVariable(0).getNameAsString();
        Statement loopBody = loop.getBody();
        if (loopBody instanceof BlockStmt block && block.getStatements().size() == 1)
            loopBody = block.getStatement(0);
        return loopBody.toString().equals(item + ".write(" + buffer + ");");
    }
}
