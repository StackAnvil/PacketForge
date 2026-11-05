package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;

/** Recovers built-in registry IDs from static registration order. */
final class RegistrySchema {
    record Entry(int id, String name, StaticCodecResolver.Value value) {}

    private final Analyzer analyzer;
    private final StaticCodecResolver resolver;

    RegistrySchema(Analyzer analyzer, StaticCodecResolver resolver) {
        this.analyzer = analyzer;
        this.resolver = resolver;
    }

    List<Entry> entries(String registry) {
        var builtIns = analyzer.index.source("net.minecraft.core.registries.BuiltInRegistries");
        Expression initializer =
                builtIns.declaration()
                        .getFieldByName(registry)
                        .orElseThrow(
                                () -> new Unsupported("Missing built-in registry: " + registry))
                        .getVariable(0)
                        .getInitializer()
                        .orElseThrow();
        if (!(initializer instanceof MethodCallExpr call))
            throw new Unsupported("Unknown registry initializer");
        Expression bootstrap =
                call.getArguments().stream()
                        .filter(a -> a instanceof LambdaExpr || a instanceof MethodReferenceExpr)
                        .findFirst()
                        .orElseThrow(() -> new Unsupported("Registry has no static bootstrap"));
        SourceIndex.Source source;
        MethodDeclaration method = null;
        if (bootstrap instanceof MethodReferenceExpr reference) {
            source =
                    analyzer.index.source(
                            analyzer.index.resolve(reference.getScope().toString(), builtIns));
            var methods = source.declaration().getMethodsByName(reference.getIdentifier());
            if (methods.size() != 1) throw new Unsupported("Ambiguous registry bootstrap");
            method = methods.getFirst();
        } else {
            var lambda = (LambdaExpr) bootstrap;
            if (!(lambda.getBody()
                            instanceof com.github.javaparser.ast.stmt.ExpressionStmt statement)
                    || !(statement.getExpression() instanceof FieldAccessExpr field))
                throw new Unsupported("Registry bootstrap is not a static class initialization");
            source =
                    analyzer.index.source(
                            analyzer.index.resolve(field.getScope().toString(), builtIns));
        }
        List<StaticCodecResolver.Registration> registrations = new ArrayList<>();
        for (var field : source.declaration().getFields())
            if (field.isStatic())
                for (var variable : field.getVariables()) {
                    if (variable.getInitializer().isEmpty()) continue;
                    var registration =
                            resolver.registration(
                                    new StaticCodecResolver.Value(
                                            variable.getInitializer().get(), source));
                    if (registration != null && registration.registry().equals(registry))
                        registrations.add(registration);
                }
        if (method != null) {
            Map<String, StaticCodecResolver.Value> variables = new HashMap<>();
            if (method.getParameters().size() == 1)
                variables.put(
                        method.getParameter(0).getNameAsString(),
                        new StaticCodecResolver.Value(
                                new FieldAccessExpr(new NameExpr("BuiltInRegistries"), registry),
                                builtIns));
            var body =
                    method.getBody()
                            .orElseThrow(() -> new Unsupported("Registry bootstrap has no body"));
            if (!body.findAll(com.github.javaparser.ast.stmt.IfStmt.class).isEmpty()
                    || !body.findAll(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty()
                    || !body.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).isEmpty())
                throw new Unsupported("Registry bootstrap has dynamic registration order");
            for (var registrationCall : body.findAll(MethodCallExpr.class))
                if (registrationCall.getNameAsString().equals("register")
                        && registrationCall
                                .getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("Registry")) {
                    var registration =
                            resolver.registration(
                                    new StaticCodecResolver.Value(
                                            registrationCall, source, variables, List.of()));
                    if (registration != null && registration.registry().equals(registry))
                        registrations.add(registration);
                }
        }
        if (registrations.isEmpty())
            throw new Unsupported("No static registry registrations found: " + registry);
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var registration : registrations) {
            if (!names.add(registration.name()))
                throw new Unsupported(
                        "Duplicate static registry registration: " + registration.name());
            entries.add(new Entry(entries.size(), registration.name(), registration.value()));
        }
        return List.copyOf(entries);
    }
}
