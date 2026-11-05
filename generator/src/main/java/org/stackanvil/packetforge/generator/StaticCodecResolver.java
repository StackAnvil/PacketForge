package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Resolves codec factories and captured constructor values without executing game code. */
final class StaticCodecResolver {
    record Value(
            Expression expression,
            SourceIndex.Source source,
            Map<String, Value> variables,
            List<BodyDeclaration<?>> locals) {
        Value(Expression expression, SourceIndex.Source source) {
            this(expression, source, Map.of(), List.of());
        }

        Value child(Expression expression) {
            return new Value(expression, source, variables, locals);
        }
    }

    record Registration(String registry, String name, Value value) {}

    static final class Rejected extends RuntimeException {
        Rejected(String reason) {
            super(reason);
        }
    }

    private final Analyzer analyzer;
    private final Set<String> active = new HashSet<>();

    StaticCodecResolver(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    Value resolve(Value value) {
        Expression expression = value.expression();
        if (expression instanceof EnclosedExpr enclosed) return resolve(value.child(enclosed.getInner()));
        if (expression instanceof NameExpr name) {
            if (value.variables().containsKey(name.getNameAsString()))
                return resolve(value.variables().get(name.getNameAsString()));
            return resolve(field(value, name.getNameAsString()));
        }
        if (expression instanceof ThisExpr && value.variables().containsKey("$this"))
            return value.variables().get("$this");
        if (expression instanceof FieldAccessExpr access) {
            if (access.getScope() instanceof ThisExpr) return resolve(field(value, access.getNameAsString()));
            if (access.getScope() instanceof NameExpr name && value.variables().containsKey(name.getNameAsString()))
                return resolve(member(value.variables().get(name.getNameAsString()), access.getNameAsString()));
            var source = analyzer.index.source(
                    analyzer.index.resolve(access.getScope().toString(), value.source()));
            return resolve(field(new Value(access, source), access.getNameAsString()));
        }
        return value;
    }

    private Value field(Value value, String name) {
        if (value.variables().containsKey("$field:" + name))
            return value.variables().get("$field:" + name);
        for (var local : value.locals())
            if (local instanceof FieldDeclaration field)
                for (var variable : field.getVariables())
                    if (variable.getNameAsString().equals(name))
                        return value.child(variable.getInitializer()
                                .orElseThrow(() -> new Unsupported("Uninitialized codec field: " + name)));
        var field = value.source()
                .declaration()
                .getFieldByName(name)
                .orElseThrow(() -> new Unsupported(
                        "Missing static codec value: " + value.source().name() + "." + name));
        var variable = field.getVariables().stream()
                .filter(v -> v.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
        return value.child(
                variable.getInitializer().orElseThrow(() -> new Unsupported("Uninitialized codec field: " + name)));
    }

    private Value member(Value receiver, String name) {
        Value object = object(receiver);
        return field(object, name);
    }

    private Value object(Value receiver) {
        Value value = resolve(receiver);
        if (!(value.expression() instanceof ObjectCreationExpr created))
            throw new Unsupported("Codec accessor receiver is not a static object: " + value.expression());
        if (created.getAnonymousClassBody().isPresent())
            return new Value(
                    created,
                    value.source(),
                    withThis(value),
                    List.copyOf(created.getAnonymousClassBody().get()));
        var owner =
                analyzer.index.source(analyzer.index.resolve(created.getType().getNameWithScope(), value.source()));
        Map<String, Value> variables = new HashMap<>();
        variables.put("$this", value);
        bindConstructor(owner, created.getArguments().stream().map(value::child).toList(), variables, new HashSet<>());
        return new Value(created, owner, variables, List.of());
    }

    private Map<String, Value> withThis(Value value) {
        Map<String, Value> variables = new HashMap<>(value.variables());
        variables.put("$this", value);
        return variables;
    }

    private void bindConstructor(
            SourceIndex.Source owner, List<Value> arguments, Map<String, Value> fields, Set<String> visited) {
        if (!visited.add(owner.name() + ":" + arguments.size())) throw new Unsupported("Recursive codec constructor");
        if (owner.declaration() instanceof RecordDeclaration record) {
            if (record.getParameters().size() != arguments.size())
                throw new Unsupported("Codec record constructor arity differs");
            for (int i = 0; i < arguments.size(); i++)
                fields.put("$field:" + record.getParameter(i).getNameAsString(), arguments.get(i));
            return;
        }
        var constructors = owner.declaration().getMembers().stream()
                .filter(m -> m instanceof ConstructorDeclaration c
                        && c.getParameters().size() == arguments.size())
                .map(m -> (ConstructorDeclaration) m)
                .toList();
        if (constructors.isEmpty() && arguments.isEmpty()) return;
        if (constructors.size() != 1) throw new Unsupported("Ambiguous static codec constructor: " + owner.name());
        var constructor = constructors.getFirst();
        Map<String, Value> variables = new HashMap<>(fields);
        for (int i = 0; i < arguments.size(); i++)
            variables.put(constructor.getParameter(i).getNameAsString(), arguments.get(i));
        for (var statement : constructor.getBody().getStatements()) {
            if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
                SourceIndex.Source parent = owner;
                if (!invocation.isThis()) {
                    if (!(owner.declaration() instanceof ClassOrInterfaceDeclaration clazz)
                            || clazz.getExtendedTypes().size() != 1)
                        throw new Unsupported("Unknown codec superclass constructor");
                    parent = analyzer.index.source(analyzer.index.resolve(
                            clazz.getExtendedTypes().get(0).getNameWithScope(), owner));
                }
                bindConstructor(
                        parent,
                        invocation.getArguments().stream()
                                .map(e -> new Value(e, owner, variables, List.of()))
                                .toList(),
                        fields,
                        visited);
            }
            if (statement instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof AssignExpr assignment
                    && assignment.getTarget() instanceof FieldAccessExpr target
                    && target.getScope() instanceof ThisExpr)
                fields.put(
                        "$field:" + target.getNameAsString(),
                        new Value(assignment.getValue(), owner, new HashMap<>(variables), List.of()));
        }
    }

    Value accessor(Value receiver, String method) {
        Value target = resolve(receiver);
        if (method.equals("streamCodec")
                && target.expression() instanceof MethodCallExpr build
                && build.getNameAsString().equals("build")
                && build.getArguments().isEmpty())
            return componentCodec(target.child(build.getScope().orElseThrow()));
        Value object = object(receiver);
        if (object.source().declaration() instanceof RecordDeclaration record
                && record.getParameters().stream()
                        .anyMatch(p -> p.getNameAsString().equals(method))) return member(receiver, method);
        var methods = object.locals().stream()
                .filter(m ->
                        m instanceof MethodDeclaration d && d.getNameAsString().equals(method))
                .map(m -> (MethodDeclaration) m)
                .toList();
        if (methods.isEmpty())
            methods = object.source().declaration().getMethodsByName(method).stream()
                    .filter(m -> m.getParameters().isEmpty())
                    .toList();
        SourceIndex.Source methodOwner = object.source();
        while (methods.isEmpty()
                && methodOwner.declaration() instanceof ClassOrInterfaceDeclaration clazz
                && clazz.getExtendedTypes().size() == 1) {
            methodOwner = analyzer.index.source(
                    analyzer.index.resolve(clazz.getExtendedTypes().get(0).getNameWithScope(), methodOwner));
            methods = methodOwner.declaration().getMethodsByName(method).stream()
                    .filter(m -> m.getParameters().isEmpty())
                    .toList();
        }
        if (methods.size() != 1) throw new Unsupported("Ambiguous codec accessor: " + method);
        return returned(methods.getFirst(), methodOwner, object.variables(), object.locals(), List.of());
    }

    private Value componentCodec(Value value) {
        Value target = resolve(value);
        if (target.expression() instanceof MethodCallExpr apply
                && apply.getNameAsString().equals("apply")
                && apply.getArguments().size() == 1
                && apply.getScope().isPresent())
            return componentCodec(
                    invoke(target.child(apply.getScope().get()), List.of(target.child(apply.getArgument(0)))));
        Value network = null, persistent = null;
        while (target.expression() instanceof MethodCallExpr call) {
            String method = call.getNameAsString();
            if (method.equals("networkSynchronized") && call.getArguments().size() == 1) {
                if (network == null) network = target.child(call.getArgument(0));
            } else if (method.equals("persistent") && call.getArguments().size() == 1) {
                if (persistent == null) persistent = target.child(call.getArgument(0));
            } else if (Set.of("cacheEncoding", "ignoreSwapAnimation").contains(method)
                    && call.getArguments().isEmpty()) {
            } else if (method.equals("builder")
                    && call.getScope().map(Object::toString).orElse("").equals("DataComponentType")) {
                if (network != null) return network;
                if (persistent != null)
                    return persistent.child(new MethodCallExpr(new NameExpr("ByteBufCodecs"), "fromCodecWithRegistries")
                            .addArgument(persistent.expression().clone()));
                throw new Unsupported("Component has no persistent or network codec");
            } else throw new Unsupported("Unknown component builder operation: " + method);
            target = resolve(target.child(call.getScope().orElseThrow()));
        }
        throw new Unsupported("Component builder is not statically bound");
    }

    Value invoke(Value function, List<Value> arguments) {
        Value target = resolve(function);
        if (target.expression() instanceof MethodReferenceExpr reference) {
            var source = analyzer.index.source(
                    analyzer.index.resolve(reference.getScope().toString(), target.source()));
            return invokeMethod(source, reference.getIdentifier(), arguments);
        }
        if (target.expression() instanceof LambdaExpr lambda) {
            if (lambda.getParameters().size() != arguments.size() || !(lambda.getBody() instanceof ExpressionStmt body))
                throw new Unsupported("Complex codec factory lambda");
            Map<String, Value> variables = new HashMap<>(target.variables());
            for (int i = 0; i < arguments.size(); i++)
                variables.put(lambda.getParameter(i).getNameAsString(), arguments.get(i));
            return new Value(body.getExpression(), target.source(), variables, target.locals());
        }
        throw new Unsupported("Not a static codec function: " + target.expression());
    }

    Wire factory(MethodCallExpr call, SourceIndex.Source source) {
        var owner = analyzer.index.source(
                analyzer.index.resolve(call.getScope().orElseThrow().toString(), source));
        var candidates = owner.declaration().getMethodsByName(call.getNameAsString()).stream()
                .filter(m -> m.isStatic()
                        && m.getTypeAsString().contains("StreamCodec")
                        && m.getParameters().size() == call.getArguments().size())
                .toList();
        if (candidates.size() != 1)
            throw new Unsupported(
                    "No unambiguous static codec factory: " + owner.name() + "." + call.getNameAsString());
        return codec(returned(
                candidates.getFirst(),
                owner,
                Map.of(),
                List.of(),
                call.getArguments().stream().map(e -> new Value(e, source)).toList()));
    }

    private Value invokeMethod(SourceIndex.Source source, String method, List<Value> arguments) {
        var candidates = source.declaration().getMethodsByName(method).stream()
                .filter(m -> m.getParameters().size() == arguments.size()
                        && m.getBody().isPresent())
                .toList();
        if (candidates.isEmpty()
                && source.declaration() instanceof ClassOrInterfaceDeclaration clazz
                && clazz.getExtendedTypes().size() == 1)
            return invokeMethod(
                    analyzer.index.source(analyzer.index.resolve(
                            clazz.getExtendedTypes().get(0).getNameWithScope(), source)),
                    method,
                    arguments);
        if (candidates.size() != 1) throw new Unsupported("Ambiguous codec factory: " + source.name() + "." + method);
        return returned(candidates.getFirst(), source, Map.of(), List.of(), arguments);
    }

    private Value returned(
            MethodDeclaration method,
            SourceIndex.Source source,
            Map<String, Value> captures,
            List<BodyDeclaration<?>> locals,
            List<Value> arguments) {
        Map<String, Value> variables = new HashMap<>(captures);
        for (int i = 0; i < arguments.size(); i++)
            variables.put(method.getParameter(i).getNameAsString(), arguments.get(i));
        for (var statement : method.getBody().orElseThrow().getStatements()) {
            if (statement instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof VariableDeclarationExpr declarations)
                for (var variable : declarations.getVariables())
                    variables.put(
                            variable.getNameAsString(),
                            new Value(
                                    variable.getInitializer().orElseThrow(), source, new HashMap<>(variables), locals));
            else if (statement instanceof ReturnStmt returned)
                return new Value(returned.getExpression().orElseThrow(), source, variables, locals);
            else
                throw new Unsupported(
                        "Codec factory contains control flow: " + source.name() + "." + method.getNameAsString());
        }
        throw new Unsupported("Codec factory does not return a value");
    }

    Registration registration(Value value) {
        Value target = resolve(value);
        if (!(target.expression() instanceof MethodCallExpr call)) return null;
        String scope = call.getScope().map(Object::toString).orElse("");
        if (scope.equals("Registry")
                && call.getNameAsString().equals("register")
                && call.getArguments().size() == 3) {
            Value registry = target.child(call.getArgument(0));
            String key = registryKey(registry);
            return new Registration(
                    key, identifier(target.child(call.getArgument(1))), target.child(call.getArgument(2)));
        }
        try {
            SourceIndex.Source source = scope.isEmpty()
                    ? target.source()
                    : analyzer.index.source(analyzer.index.resolve(scope, target.source()));
            return registration(invokeMethod(
                    source,
                    call.getNameAsString(),
                    call.getArguments().stream().map(target::child).toList()));
        } catch (Unsupported unknown) {
            return null;
        }
    }

    String registryKey(Value value) {
        Expression expression = value.expression();
        if (expression instanceof NameExpr name && value.variables().containsKey(name.getNameAsString()))
            return registryKey(value.variables().get(name.getNameAsString()));
        if (expression instanceof FieldAccessExpr field
                && field.getScope().toString().endsWith("BuiltInRegistries")) return field.getNameAsString();
        throw new Unsupported("Registry is not a bound built-in registry: " + expression);
    }

    String identifier(Value value) {
        Expression expression = value.expression();
        if (expression instanceof StringLiteralExpr string)
            return string.asString().contains(":") ? string.asString() : "minecraft:" + string.asString();
        if (expression instanceof NameExpr name && value.variables().containsKey(name.getNameAsString()))
            return identifier(value.variables().get(name.getNameAsString()));
        if (expression instanceof MethodCallExpr call
                && call.getScope().map(Object::toString).orElse("").equals("Identifier")
                && Set.of("withDefaultNamespace", "parse").contains(call.getNameAsString())
                && call.getArguments().size() == 1) return identifier(value.child(call.getArgument(0)));
        throw new Unsupported("Registry entry name is not a static identifier: " + expression);
    }

    Wire codec(Value value) {
        String token =
                value.source().name() + ":" + value.expression() + ":" + System.identityHashCode(value.variables());
        if (!active.add(token)) throw new Unsupported("Recursive static codec factory");
        try {
            return codecValue(value);
        } finally {
            active.remove(token);
        }
    }

    private Wire codecValue(Value value) {
        Expression expression = value.expression();
        if (expression instanceof NullLiteralExpr) throw new Rejected("Source codec requires a non-null payload codec");
        if (expression instanceof NameExpr name && value.variables().containsKey(name.getNameAsString()))
            return codec(value.variables().get(name.getNameAsString()));
        if (expression instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr)
            return codec(field(value, field.getNameAsString()));
        if (expression instanceof FieldAccessExpr field
                && field.getScope() instanceof NameExpr name
                && value.variables().containsKey(name.getNameAsString()))
            return codec(member(value.variables().get(name.getNameAsString()), field.getNameAsString()));
        if (expression instanceof NameExpr || expression instanceof FieldAccessExpr) {
            boolean bound =
                    expression instanceof NameExpr name && value.variables().containsKey(name.getNameAsString())
                            || expression instanceof FieldAccessExpr access
                                    && (access.getScope() instanceof ThisExpr
                                            || access.getScope() instanceof NameExpr name
                                                    && value.variables().containsKey(name.getNameAsString()));
            if (!bound)
                try {
                    return analyzer.codec(expression, value.source());
                } catch (Unsupported unresolved) {
                }
            Value resolved;
            try {
                resolved = resolve(value);
            } catch (Unsupported missingValue) {
                return analyzer.codec(expression, value.source());
            }
            return codec(resolved);
        }
        if (!(expression instanceof MethodCallExpr call)) return analyzer.codec(expression, value.source());
        String scope = call.getScope().map(Object::toString).orElse(""), method = call.getNameAsString();
        if (scope.equals("Objects")
                && method.equals("requireNonNull")
                && call.getArguments().size() == 1) return codec(value.child(call.getArgument(0)));
        if (method.equals("apply")
                && call.getScope().isPresent()
                && call.getScope().get() instanceof NameExpr variable
                && value.variables().containsKey(variable.getNameAsString())) {
            Value receiver = value.variables().get(variable.getNameAsString());
            try {
                receiver = resolve(receiver);
            } catch (Unsupported nativeCodec) {
            }
            if (receiver.expression() instanceof LambdaExpr || receiver.expression() instanceof MethodReferenceExpr)
                return codec(invoke(
                        receiver, call.getArguments().stream().map(value::child).toList()));
        }
        MethodCallExpr translated = call.clone();
        if (Set.of("map", "apply", "dispatch").contains(method)
                && call.getScope().isPresent())
            translated.setScope(analyzer.bind(codec(value.child(call.getScope().get()))));
        boolean nativeBuffer =
                scope.equals("ByteBufCodecs") || scope.equals("net.minecraft.network.codec.ByteBufCodecs");
        if (scope.equals("StreamCodec") && method.equals("composite"))
            for (int i = 0; i < call.getArguments().size() - 1; i += 2)
                translated.setArgument(i, analyzer.bind(codec(value.child(call.getArgument(i)))));
        if (nativeBuffer) {
            translated.setScope(new NameExpr("ByteBufCodecs"));
            if (method.equals("optional"))
                translated.setArgument(0, analyzer.bind(codec(value.child(call.getArgument(0)))));
            if (method.equals("map") && call.getArguments().size() >= 3)
                for (int i : new int[] {1, 2})
                    translated.setArgument(i, analyzer.bind(codec(value.child(call.getArgument(i)))));
            if (Set.of("either", "holder").contains(method)) {
                int first = method.equals("holder") ? 1 : 0;
                for (int i = first; i < call.getArguments().size(); i++)
                    translated.setArgument(i, analyzer.bind(codec(value.child(call.getArgument(i)))));
            }
            if (Set.of("registry", "holderRegistry").contains(method)
                    && call.getArguments().size() == 1
                    && call.getArgument(0) instanceof MethodCallExpr key
                    && key.getNameAsString().equals("key"))
                translated.setArgument(
                        0,
                        new FieldAccessExpr(
                                new NameExpr("net.minecraft.core.registries.Registries"),
                                registryKey(value.child(key.getScope().orElseThrow()))));
            for (int i = 0; i < call.getArguments().size(); i++)
                if (call.getArgument(i) instanceof NameExpr argument
                        && value.variables().containsKey(argument.getNameAsString())
                        && !Set.of("optional", "map", "holder", "either").contains(method))
                    translated.setArgument(i, scalar(value.variables().get(argument.getNameAsString())));
        }
        if (nativeBuffer
                || Set.of("StreamCodec", "ResourceKey").contains(scope)
                || Set.of("map", "apply", "dispatch").contains(method))
            return analyzer.codec(translated, value.source());
        if (call.getScope().isPresent()
                && call.getArguments().isEmpty()
                && call.getScope().get() instanceof NameExpr variable
                && value.variables().containsKey(variable.getNameAsString()))
            return codec(accessor(value.variables().get(variable.getNameAsString()), method));
        try {
            var source = scope.isEmpty()
                    ? value.source()
                    : analyzer.index.source(analyzer.index.resolve(scope, value.source()));
            return codec(invokeMethod(
                    source,
                    method,
                    call.getArguments().stream().map(value::child).toList()));
        } catch (Unsupported helper) {
            try {
                return analyzer.codec(expression, value.source());
            } catch (Unsupported fallback) {
                throw new Unsupported(fallback.getMessage() + "; factory: " + helper.getMessage());
            }
        }
    }

    private Expression scalar(Value value) {
        if (value.expression() instanceof NameExpr name && value.variables().containsKey(name.getNameAsString()))
            return scalar(value.variables().get(name.getNameAsString()));
        return value.expression().clone();
    }
}
