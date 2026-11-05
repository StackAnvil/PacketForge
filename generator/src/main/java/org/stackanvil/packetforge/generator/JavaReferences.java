package org.stackanvil.packetforge.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Resolves generated schema symbols in Java fragments at the IR boundary. String values are never scanned. */
final class JavaReferences {
    private static final String GENERATED = "org.stackanvil.packetforge.protocol.";
    private static final String TABLES = GENERATED + "GeneratedWireCodecs";
    private final JavaParser parser =
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Set<String> modelNames;
    private final Map<String, Set<SchemaReference>> cache = new HashMap<>();

    JavaReferences(Set<String> modelNames) {
        this.modelNames = Set.copyOf(modelNames);
    }

    Set<SchemaReference> type(String source) {
        return cache.computeIfAbsent("type:" + source, ignored -> collect(parsed(parser.parseType(source), source)));
    }

    Set<SchemaReference> expression(String source) {
        if (source.isBlank()) return Set.of();
        return cache.computeIfAbsent(
                "expression:" + source, ignored -> collect(parsed(parser.parseExpression(source), source)));
    }

    Set<SchemaReference> statements(String source) {
        if (source.isBlank()) return Set.of();
        return cache.computeIfAbsent(
                "statements:" + source, ignored -> collect(parsed(parser.parseBlock("{" + source + "}"), source)));
    }

    Set<SchemaReference> member(String source) {
        return cache.computeIfAbsent(
                "member:" + source, ignored -> collect(parsed(parser.parseBodyDeclaration(source), source)));
    }

    private Node parsed(ParseResult<? extends Node> result, String source) {
        if (!result.isSuccessful()) {
            throw new Unsupported("Cannot parse generated schema Java: "
                    + result.getProblems().getFirst().getMessage() + "; fragment: " + source);
        }
        return result.getResult().orElseThrow();
    }

    private Set<SchemaReference> collect(Node node) {
        Set<SchemaReference> references = new HashSet<>();
        for (var type : node.findAll(ClassOrInterfaceType.class)) {
            if (type.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                    && parent.getScope().orElse(null) == type) continue;
            model(type.getNameWithScope()).ifPresent(references::add);
        }
        for (var field : node.findAll(FieldAccessExpr.class)) {
            if (field.getParentNode().orElse(null) instanceof FieldAccessExpr parent && parent.getScope() == field)
                continue;
            if (field.getParentNode().orElse(null) instanceof MethodCallExpr parent
                    && parent.getScope().orElse(null) == field) continue;
            if (field.getParentNode().orElse(null) instanceof MethodReferenceExpr parent && parent.getScope() == field)
                continue;
            qualifiedName(field.getScope()).ifPresent(owner -> member(owner, field.getNameAsString(), references));
        }
        for (var call : node.findAll(MethodCallExpr.class)) {
            call.getScope()
                    .flatMap(JavaReferences::qualifiedName)
                    .ifPresent(owner -> member(owner, call.getNameAsString(), references));
        }
        for (var reference : node.findAll(MethodReferenceExpr.class)) {
            qualifiedName(reference.getScope())
                    .ifPresent(owner -> member(owner, reference.getIdentifier(), references));
        }
        return Set.copyOf(references);
    }

    private void member(String owner, String name, Set<SchemaReference> references) {
        if (owner.equals(TABLES) || owner.equals("GeneratedWireCodecs")) {
            String table = name.endsWith("_payloads") ? name.substring(0, name.length() - "_payloads".length()) : name;
            references.add(new SchemaReference.Dispatch(table));
        } else model(owner).ifPresent(references::add);
    }

    private Optional<SchemaReference.Model> model(String name) {
        String candidate = name;
        while (candidate.contains(".")) {
            if (modelNames.contains(candidate)) return Optional.of(new SchemaReference.Model(candidate));
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        if (modelNames.contains(name)
                || name.startsWith(GENERATED + "packet.")
                || name.startsWith(GENERATED + "type.")) {
            return Optional.of(new SchemaReference.Model(name));
        }
        return Optional.empty();
    }

    private static Optional<String> qualifiedName(Expression expression) {
        if (expression instanceof NameExpr name) return Optional.of(name.getNameAsString());
        if (expression instanceof FieldAccessExpr field) {
            return qualifiedName(field.getScope()).map(scope -> scope + "." + field.getNameAsString());
        }
        if (expression instanceof TypeExpr type && type.getType() instanceof ClassOrInterfaceType name) {
            return Optional.of(name.getNameWithScope());
        }
        return Optional.empty();
    }
}
