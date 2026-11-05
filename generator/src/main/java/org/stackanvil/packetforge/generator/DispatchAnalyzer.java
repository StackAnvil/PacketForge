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
        if (!analyzer.activeDispatches.add(name)) throw new Unsupported("Recursive dispatch requires a completed payload schema: " + source.name());
        try {
        if (!(dispatch.getArgument(1) instanceof MethodReferenceExpr accessor)) throw new Unsupported("Dispatch codec selector is not a method reference");
        var owner = analyzer.index.source(analyzer.index.resolve(accessor.getScope().toString(), source));
        List<DispatchCase> cases;
        if (owner.declaration() instanceof EnumDeclaration enumeration) cases = enumeration(enumeration, accessor.getIdentifier(), owner);
        else if (key.shape().startsWith("REGISTRY:")) cases = registry(dispatch, key, accessor, owner, source);
        else throw new Unsupported("Unknown dispatch key codec: " + dispatch);
        String type = "org.stackanvil.packetforge.commons.Variant<" + boxed(key.type()) + ">";
        List<String> entries = new ArrayList<>();
        for (var branch : cases) {
            String discriminator = key.shape().startsWith("enum:") ? key.type() + "." + branch.key() : branch.key();
            entries.add("java.util.Map.entry(" + discriminator + ", (PacketCodec<?>) " + branch.value().codec() + ")");
        }
        Wire implementation = new Wire(type, "WireCodecs.dispatch(" + key.codec() + ", java.util.Map.ofEntries(" + String.join(", ", entries) + "))", "dispatch:" + name);
        Dispatch definition = new Dispatch(name, key, List.copyOf(cases), source.name(), dispatch.toString(), implementation, key.shape().startsWith("REGISTRY:") ? key.shape().substring(key.shape().lastIndexOf('.') + 1) : "");
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
