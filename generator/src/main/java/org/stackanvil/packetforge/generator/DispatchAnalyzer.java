package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Recovers discriminator-to-payload codec tables from static declarations. */
final class DispatchAnalyzer {
    private final Analyzer analyzer;
    DispatchAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    Wire lower(MethodCallExpr dispatch, SourceIndex.Source source) {
        Wire key = analyzer.codec(dispatch.getScope().orElseThrow(), source);
        String identity = source.name() + "#" + key.type() + "#" + key.codec() + "#" + key.shape().replace("net.minecraft.core.registries.", "") + "#" + dispatch.getArguments();
        String name = "dispatch_" + Integer.toUnsignedString(identity.hashCode());
        if (analyzer.dispatches.containsKey(name)) return reference(analyzer.dispatches.get(name));
        if (!analyzer.activeDispatches.add(name)) return new Wire("org.stackanvil.packetforge.commons.Variant<" + boxed(key.type()) + ">", "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name, "dispatch:" + name);
        try {
        if (!(dispatch.getArgument(1) instanceof MethodReferenceExpr accessor)) throw new Unsupported("Dispatch codec selector is not a method reference");
        var owner = analyzer.index.source(analyzer.index.resolve(accessor.getScope().toString(), source));
        List<DispatchCase> cases;
        if (owner.declaration() instanceof EnumDeclaration enumeration) cases = enumeration(enumeration, accessor.getIdentifier(), owner);
        else if (key.shape().startsWith("REGISTRY:")) cases = registry(dispatch, key, accessor, owner, source);
        else if (key.shape().startsWith("either<REGISTRY:")) cases = union(key, accessor, owner);
        else throw new Unsupported("Unknown dispatch key codec: " + dispatch);
        String type = "org.stackanvil.packetforge.commons.Variant<" + boxed(key.type()) + ">";
        List<String> entries = new ArrayList<>();
        for (var branch : cases) {
            String discriminator = key.shape().startsWith("enum:") ? key.type() + "." + branch.key() : branch.key().contains(":") ? "org.stackanvil.packetforge.commons.Either.<Integer, Integer>" + branch.key().replace(":", "(") + ")" : branch.key();
            entries.add("java.util.Map.entry(" + discriminator + ", (PacketCodec<?>) " + branch.value().codec() + ")");
        }
        Wire implementation = new Wire(type, "WireCodecs.dispatch(" + key.codec() + ", java.util.Map.<" + boxed(key.type()) + ", PacketCodec<?>>ofEntries(" + String.join(", ", entries) + "))", "dispatch:" + name);
        String sourceField = "STREAM_CODEC";
        for (var field : source.declaration().getFields()) for (var variable : field.getVariables()) if (variable.getInitializer().isPresent() && variable.getInitializer().get().findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("dispatch") && c.getArguments().equals(dispatch.getArguments()))) sourceField = variable.getNameAsString();
        Dispatch definition = new Dispatch(name, key, List.copyOf(cases), source.name(), dispatch.toString(), implementation, key.shape().startsWith("REGISTRY:") ? key.shape().substring(key.shape().lastIndexOf('.') + 1) : key.shape().startsWith("either<REGISTRY:") ? String.join("|", registryNames(key)) : "", sourceField);
        analyzer.dispatches.put(name, definition); return reference(definition);
        } finally { analyzer.activeDispatches.remove(name); }
    }
    private List<DispatchCase> registry(MethodCallExpr dispatch, Wire key, MethodReferenceExpr accessor, SourceIndex.Source accessorOwner, SourceIndex.Source source) {
        String registry = key.shape().substring(key.shape().lastIndexOf('.') + 1);
        String diagnostic = registry + "#" + accessorOwner.name() + "." + accessor.getIdentifier();
        var resolver = new StaticCodecResolver(analyzer);
        List<RegistrySchema.Entry> entries = new RegistrySchema(analyzer, resolver).entries(registry);
        List<DispatchCase> cases = new ArrayList<>(); List<RegistryGap> gaps = new ArrayList<>();
        boolean factory = accessorOwner.declaration().getMethodsByName(accessor.getIdentifier()).stream().anyMatch(m -> m.isStatic() && m.getParameters().size() == 1);
        for (var entry : entries) {
            try {
                var value = factory ? resolver.invoke(new StaticCodecResolver.Value(accessor, source), List.of(entry.value())) : resolver.accessor(entry.value(), accessor.getIdentifier());
                Wire codec = resolver.codec(value);
                cases.add(new DispatchCase(Integer.toString(entry.id()), codec, entry.name()));
            } catch (StaticCodecResolver.Rejected rejected) {
                Wire codec = new Wire("org.stackanvil.packetforge.commons.Unit", "WireCodecs.rejected(" + new com.google.gson.Gson().toJson(rejected.getMessage()) + ")", "REJECTED:" + rejected.getMessage());
                cases.add(new DispatchCase(Integer.toString(entry.id()), codec, entry.name()));
            } catch (Unsupported unsupported) { gaps.add(new RegistryGap(entry.id(), entry.name(), unsupported.getMessage())); }
        }
        if (!gaps.isEmpty()) {
            analyzer.registryGaps.put(diagnostic, List.copyOf(gaps));
            throw new Unsupported("Registry " + registry + " has " + gaps.size() + " unresolved payload codecs; first: " + gaps.getFirst().name() + ": " + gaps.getFirst().reason());
        }
        analyzer.registryGaps.remove(diagnostic);
        return cases;
    }
    private List<String> registryNames(Wire key) {
        var names = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("REGISTRY:(?:[a-zA-Z0-9_.]*\\.)?([A-Z_]+)").matcher(key.shape());
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
    private List<DispatchCase> union(Wire key, MethodReferenceExpr accessor, SourceIndex.Source owner) {
        // Distribute a mapped Either discriminator over its two registry tables.
        var initializer = owner.declaration().getFieldByName("STREAM_CODEC").flatMap(f -> f.getVariable(0).getInitializer()).orElseThrow(() -> new Unsupported("Union key has no declared codec"));
        if (!(initializer instanceof MethodCallExpr mapping) || !mapping.getNameAsString().equals("map") || !(mapping.getArgument(0) instanceof MethodReferenceExpr factory)) throw new Unsupported("Union dispatch has no static domain mapping");
        var factories = owner.declaration().getMethodsByName(factory.getIdentifier());
        if (factories.size() != 1 || factories.getFirst().getParameters().size() != 1) throw new Unsupported("Ambiguous union key factory");
        var body = factories.getFirst().getBody().orElseThrow();
        if (body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ReturnStmt returned)) throw new Unsupported("Union key factory has control flow");
        Expression expression = returned.getExpression().orElseThrow();
        while (expression instanceof CastExpr || expression instanceof EnclosedExpr) expression = expression instanceof CastExpr cast ? cast.getExpression() : expression.asEnclosedExpr().getInner();
        if (!(expression instanceof MethodCallExpr map) || !map.getNameAsString().equals("map") || map.getArguments().size() != 2 || !map.getScope().map(Object::toString).orElse("").equals(factories.getFirst().getParameter(0).getNameAsString())) throw new Unsupported("Union key factory does not map its input branches");
        List<String> registries = registryNames(key);
        if (registries.size() != 2) throw new Unsupported("Union dispatch needs two registry keys");
        var resolver = new StaticCodecResolver(analyzer); List<DispatchCase> cases = new ArrayList<>(); List<RegistryGap> gaps = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            String registry = registries.get(side), prefix = side == 0 ? "left:" : "right:";
            for (var entry : new RegistrySchema(analyzer, resolver).entries(registry)) try {
                var receiver = resolver.invoke(new StaticCodecResolver.Value(map.getArgument(side), owner), List.of(entry.value()));
                Wire codec = resolver.codec(resolver.accessor(receiver, accessor.getIdentifier()));
                cases.add(new DispatchCase(prefix + entry.id(), codec, entry.name()));
            } catch (Unsupported unsupported) { gaps.add(new RegistryGap(entry.id(), registry + ":" + entry.name(), unsupported.getMessage())); }
        }
        String diagnostic = String.join("|", registries) + "#" + owner.name() + "." + accessor.getIdentifier();
        if (!gaps.isEmpty()) { analyzer.registryGaps.put(diagnostic, List.copyOf(gaps)); throw new Unsupported("Union registry has " + gaps.size() + " unresolved payloads; first: " + gaps.getFirst().reason()); }
        analyzer.registryGaps.remove(diagnostic); return cases;
    }
    private Wire reference(Dispatch dispatch) { return new Wire(dispatch.implementation().type(), "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + dispatch.name(), "dispatch:" + dispatch.name()); }
    private List<DispatchCase> enumeration(EnumDeclaration enumeration, String methodName, SourceIndex.Source source) {
        var methods = enumeration.getMethodsByName(methodName);
        if (methods.size() != 1) throw new Unsupported("Ambiguous enum dispatch accessor");
        var body = methods.getFirst().getBody().orElseThrow();
        if (body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ReturnStmt returned)) throw new Unsupported("Enum dispatch accessor transforms its codec");
        Expression result = returned.getExpression().orElseThrow();
        String field;
        if (result instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) field = access.getNameAsString();
        else if (result instanceof MethodCallExpr call && call.getNameAsString().equals("get") && call.getArguments().isEmpty() && call.getScope().orElse(null) instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) field = access.getNameAsString();
        else throw new Unsupported("Unknown enum dispatch accessor");
        var constructors = enumeration.getMembers().stream().filter(m -> m instanceof ConstructorDeclaration).map(m -> (ConstructorDeclaration) m).toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous enum dispatch constructor");
        var constructor = constructors.getFirst(); String parameter = null;
        for (var assignment : constructor.findAll(AssignExpr.class)) if (assignment.getTarget().toString().equals("this." + field)) {
            if (assignment.getValue() instanceof NameExpr name) parameter = name.getNameAsString();
            else {
                // Supplier memoization retains the supplied codec function.
                var references = assignment.getValue().findAll(MethodReferenceExpr.class);
                if (references.size() == 1 && references.getFirst().getIdentifier().equals("get")) parameter = references.getFirst().getScope().toString();
            }
        }
        int position = -1;
        for (int i = 0; i < constructor.getParameters().size(); i++) if (constructor.getParameter(i).getNameAsString().equals(parameter)) position = i;
        if (position < 0) throw new Unsupported("Cannot bind enum dispatch codec parameter");
        List<DispatchCase> cases = new ArrayList<>();
        for (var constant : enumeration.getEntries()) {
            Expression expression = constant.getArgument(position);
            if (expression instanceof LambdaExpr lambda && lambda.getParameters().isEmpty() && lambda.getBody() instanceof ExpressionStmt statement) expression = statement.getExpression();
            cases.add(new DispatchCase(constant.getNameAsString(), analyzer.codec(expression, source)));
        }
        return cases;
    }
}
