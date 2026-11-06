package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.*;
import java.util.*;
import org.stackanvil.packetforge.generator.Analyzer.Wire;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Validates the source status schemas before using the shared typed JSON implementation. */
final class StatusWire {
    private static final String STATUS = "net.minecraft.network.protocol.status.ServerStatus";

    private record Property(String name, String codec, String optional, String fallback) {}

    private static final AstPattern FAVICON = AstPattern.expression("""
        Codec.STRING.comapFlatMap($string -> {
            if (!$string.startsWith("data:image/png;base64,")) {
                return DataResult.error(() -> "Unknown format");
            }
            try {
                String $base64 = $string.substring("data:image/png;base64,".length()).replaceAll("\\n", "");
                byte[] $bytes = Base64.getDecoder().decode($base64.getBytes(StandardCharsets.UTF_8));
                return DataResult.success(new $Icon($bytes));
            } catch (IllegalArgumentException $exception) {
                return DataResult.error(() -> "Malformed base64 server icon");
            }
        }, $icon -> "data:image/png;base64," + new String(Base64.getEncoder().encode($icon.iconBytes), StandardCharsets.UTF_8))
        """);

    private StatusWire() {}

    static Optional<Wire> json(
            Expression expression, Expression operations, SourceIndex.Source source, SourceIndex index, Wire json) {
        while (expression instanceof EnclosedExpr || expression instanceof CastExpr)
            expression = expression instanceof EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : expression.asCastExpr().getExpression();
        String owner;
        if (expression instanceof NameExpr name && name.getNameAsString().equals("CODEC")) owner = source.name();
        else if (expression instanceof FieldAccessExpr field
                && field.getNameAsString().equals("CODEC"))
            owner = index.resolve(field.getScope().toString(), source);
        else return Optional.empty();
        if (!owner.equals(STATUS)) return Optional.empty();
        if (!jsonOperations(operations, source, index, new HashSet<>()))
            throw new Unsupported("Unknown status JSON operations: " + operations);
        validate(index);
        return Optional.of(new Wire(
                "org.stackanvil.packetforge.commons.status.ServerStatus",
                "org.stackanvil.packetforge.commons.status.StatusCodecs.status(" + json.codec() + ")",
                json.shape() + ":" + expression));
    }

    private static boolean jsonOperations(
            Expression expression, SourceIndex.Source source, SourceIndex index, Set<String> active) {
        while (expression instanceof EnclosedExpr || expression instanceof CastExpr)
            expression = expression instanceof EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : expression.asCastExpr().getExpression();
        if (expression instanceof MethodCallExpr call
                && call.getNameAsString().equals("createSerializationContext")
                && call.getArguments().size() == 1
                && identity(call.getScope().orElseThrow(), source, index)
                        .equals("net.minecraft.core.RegistryAccess.EMPTY"))
            return jsonOperations(call.getArgument(0), source, index, active);
        String field;
        if (expression instanceof FieldAccessExpr access) {
            String owner = index.resolve(access.getScope().toString(), source);
            if (owner.equals("com.mojang.serialization.JsonOps"))
                return access.getNameAsString().equals("INSTANCE");
            source = index.source(owner);
            field = access.getNameAsString();
        } else if (expression instanceof NameExpr name) field = name.getNameAsString();
        else return false;
        String identity = source.name() + "#" + field;
        if (!active.add(identity)) throw new Unsupported("Recursive status JSON operations: " + identity);
        var initializer = source.declaration()
                .getFieldByName(field)
                .flatMap(value -> value.getVariable(0).getInitializer());
        return initializer.isPresent() && jsonOperations(initializer.get(), source, index, active);
    }

    private static void validate(SourceIndex index) {
        record(
                index,
                STATUS,
                List.of(
                        new Property(
                                "description",
                                "net.minecraft.network.chat.ComponentSerialization.CODEC",
                                "lenientOptionalFieldOf",
                                "net.minecraft.network.chat.CommonComponents.EMPTY"),
                        new Property("players", STATUS + ".Players.CODEC", "lenientOptionalFieldOf", ""),
                        new Property("version", STATUS + ".Version.CODEC", "lenientOptionalFieldOf", ""),
                        new Property("favicon", STATUS + ".Favicon.CODEC", "lenientOptionalFieldOf", ""),
                        new Property(
                                "enforcesSecureChat",
                                "com.mojang.serialization.Codec.BOOL",
                                "lenientOptionalFieldOf",
                                "false")));
        record(
                index,
                STATUS + ".Players",
                List.of(
                        new Property("max", "com.mojang.serialization.Codec.INT", "fieldOf", ""),
                        new Property("online", "com.mojang.serialization.Codec.INT", "fieldOf", ""),
                        new Property(
                                "sample",
                                "net.minecraft.server.players.NameAndId.CODEC.listOf()",
                                "lenientOptionalFieldOf",
                                "java.util.List.of()")));
        record(
                index,
                STATUS + ".Version",
                List.of(
                        new Property("name", "com.mojang.serialization.Codec.STRING", "fieldOf", ""),
                        new Property("protocol", "com.mojang.serialization.Codec.INT", "fieldOf", "")));
        record(
                index,
                "net.minecraft.server.players.NameAndId",
                List.of(
                        new Property("id", "net.minecraft.core.UUIDUtil.STRING_CODEC", "fieldOf", ""),
                        new Property("name", "com.mojang.serialization.Codec.STRING", "fieldOf", "")));
        var favicon = index.source(STATUS + ".Favicon");
        if (!(favicon.declaration() instanceof RecordDeclaration record)
                || record.getParameters().size() != 1
                || !record.getParameter(0).getTypeAsString().equals("byte[]")
                || !record.getParameter(0).getNameAsString().equals("iconBytes"))
            throw new Unsupported("Unknown status favicon record");
        plainRecord(record);
        FAVICON.match(initializer(favicon)).orElseThrow(() -> new Unsupported("Unknown status favicon codec"));
        var common = index.source("net.minecraft.network.chat.CommonComponents");
        var empty = common.declaration()
                .getFieldByName("EMPTY")
                .orElseThrow()
                .getVariable(0)
                .getInitializer()
                .orElseThrow();
        if (!identity(empty, common, index).equals("net.minecraft.network.chat.Component.empty()"))
            throw new Unsupported("Unknown default status description");
    }

    private static void record(SourceIndex index, String name, List<Property> properties) {
        var source = index.source(name);
        if (!(source.declaration() instanceof RecordDeclaration record)
                || record.getParameters().size() != properties.size())
            throw new Unsupported("Unknown status record: " + name);
        plainRecord(record);
        Expression init = initializer(source);
        if (!(init instanceof MethodCallExpr create)
                || !create.getNameAsString().equals("create")
                || !create.getScope()
                        .map(scope -> index.resolve(scope.toString(), source))
                        .orElse("")
                        .equals("com.mojang.serialization.codecs.RecordCodecBuilder")
                || create.getArguments().size() != 1
                || !(create.getArgument(0) instanceof LambdaExpr lambda)
                || lambda.getParameters().size() != 1
                || !lambda.getBody().isExpressionStmt()
                || !(lambda.getBody().asExpressionStmt().getExpression() instanceof MethodCallExpr apply)
                || !apply.getNameAsString().equals("apply")
                || apply.getArguments().size() != 2
                || !apply.getArgument(0)
                        .toString()
                        .equals(lambda.getParameter(0).getNameAsString())
                || !(apply.getArgument(1) instanceof MethodReferenceExpr constructor)
                || !constructor.getIdentifier().equals("new")
                || !index.resolve(constructor.getScope().toString(), source).equals(name)
                || !(apply.getScope().orElse(null) instanceof MethodCallExpr group)
                || !group.getNameAsString().equals("group")
                || !group.getScope()
                        .map(Object::toString)
                        .orElse("")
                        .equals(lambda.getParameter(0).getNameAsString())
                || group.getArguments().size() != properties.size())
            throw new Unsupported("Unknown status record codec: " + name);
        for (int i = 0; i < properties.size(); i++) {
            var expected = properties.get(i);
            if (!(group.getArgument(i) instanceof MethodCallExpr getter)
                    || !getter.getNameAsString().equals("forGetter")
                    || getter.getArguments().size() != 1
                    || !(getter.getArgument(0) instanceof MethodReferenceExpr accessor)
                    || !index.resolve(accessor.getScope().toString(), source).equals(name)
                    || !accessor.getIdentifier().equals(record.getParameter(i).getNameAsString())
                    || !(getter.getScope().orElse(null) instanceof MethodCallExpr field)
                    || !field.getNameAsString().equals(expected.optional())
                    || field.getArguments().size() != (expected.fallback().isEmpty() ? 1 : 2)
                    || !field.getArgument(0).isStringLiteralExpr()
                    || !field.getArgument(0).asStringLiteralExpr().asString().equals(expected.name())
                    || !identity(field.getScope().orElseThrow(), source, index).equals(expected.codec())
                    || !expected.fallback().isEmpty()
                            && !identity(field.getArgument(1), source, index).equals(expected.fallback()))
                throw new Unsupported("Unknown status field codec: " + name + "." + expected.name());
        }
    }

    private static void plainRecord(RecordDeclaration record) {
        for (var member : record.getMembers()) {
            if (member instanceof com.github.javaparser.ast.body.CompactConstructorDeclaration
                    || member instanceof com.github.javaparser.ast.body.ConstructorDeclaration constructor
                            && constructor.getParameters().size()
                                    == record.getParameters().size())
                throw new Unsupported("Unknown status record construction: " + record.getNameAsString());
        }
        for (var parameter : record.getParameters())
            if (record.getMethodsByName(parameter.getNameAsString()).stream()
                    .anyMatch(method ->
                            !method.isStatic() && method.getParameters().isEmpty()))
                throw new Unsupported("Unknown status record accessor: " + parameter.getNameAsString());
    }

    private static Expression initializer(SourceIndex.Source source) {
        return source.declaration()
                .getFieldByName("CODEC")
                .orElseThrow(() -> new Unsupported("Missing status codec: " + source.name()))
                .getVariable(0)
                .getInitializer()
                .orElseThrow();
    }

    private static String identity(Expression expression, SourceIndex.Source source, SourceIndex index) {
        if (expression instanceof FieldAccessExpr field)
            return index.resolve(field.getScope().toString(), source) + "." + field.getNameAsString();
        if (expression instanceof MethodCallExpr call && call.getArguments().isEmpty())
            return identity(call.getScope().orElseThrow(), source, index) + "." + call.getNameAsString() + "()";
        if (expression instanceof NameExpr name) return index.resolve(name.getNameAsString(), source);
        if (expression instanceof BooleanLiteralExpr literal) return Boolean.toString(literal.getValue());
        throw new Unsupported("Unknown status codec expression: " + expression);
    }
}
