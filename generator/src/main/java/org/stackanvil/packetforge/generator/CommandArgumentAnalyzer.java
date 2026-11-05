package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Recovers command argument IDs and their network serializer programs. */
final class CommandArgumentAnalyzer {
    private final Analyzer analyzer;

    CommandArgumentAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    Wire table() {
        var source =
                analyzer.index.source("net.minecraft.commands.synchronization.ArgumentTypeInfos");
        var bootstrap = method(source, "bootstrap", 1);
        var register = method(source, "register", 4);
        String registry = register.getParameter(0).getNameAsString(),
                id = register.getParameter(1).getNameAsString(),
                type = register.getParameter(2).getNameAsString(),
                info = register.getParameter(3).getNameAsString();
        var helper = register.getBody().orElseThrow().getStatements();
        if (helper.size() != 2
                || !helper.get(0).toString().equals("BY_CLASS.put(" + type + ", " + info + ");")
                || !helper.get(1)
                        .toString()
                        .equals(
                                "return Registry.register("
                                        + registry
                                        + ", "
                                        + id
                                        + ", "
                                        + info
                                        + ");"))
            throw new Unsupported("Command registry helper has unknown registration operations");
        var builtIns = analyzer.index.source("net.minecraft.core.registries.BuiltInRegistries");
        var initializer =
                builtIns.declaration()
                        .getFieldByName("COMMAND_ARGUMENT_TYPE")
                        .orElseThrow()
                        .getVariable(0)
                        .getInitializer()
                        .orElseThrow();
        if (initializer.findAll(MethodReferenceExpr.class).stream()
                .noneMatch(
                        r ->
                                r.getIdentifier().equals("bootstrap")
                                        && analyzer.index
                                                .resolve(r.getScope().toString(), builtIns)
                                                .equals(source.name())))
            throw new Unsupported("Command registry has a different bootstrap");
        String parameter = bootstrap.getParameter(0).getNameAsString();
        List<DispatchCase> cases = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<RegistryGap> gaps = new ArrayList<>();
        for (var statement : bootstrap.getBody().orElseThrow().getStatements()) {
            Expression expression =
                    statement instanceof ExpressionStmt e
                            ? e.getExpression()
                            : statement instanceof ReturnStmt r
                                    ? r.getExpression().orElse(null)
                                    : null;
            if (!(expression instanceof MethodCallExpr call)
                    || call.getScope().isPresent()
                    || !call.getNameAsString().equals("register")
                    || call.getArguments().size() != 4
                    || !call.getArgument(0).toString().equals(parameter)
                    || !(call.getArgument(1) instanceof StringLiteralExpr name))
                throw new Unsupported(
                        "Command registry has unknown or dynamic registration operations");
            String key =
                    name.asString().contains(":")
                            ? name.asString()
                            : "minecraft:" + name.asString();
            if (!names.add(key))
                throw new Unsupported("Duplicate command argument registration: " + key);
            int wireId = cases.size() + gaps.size();
            try {
                cases.add(
                        new DispatchCase(
                                Integer.toString(wireId),
                                serializer(call.getArgument(3), source),
                                key));
            } catch (Unsupported missing) {
                gaps.add(new RegistryGap(wireId, key, missing.getMessage()));
            }
        }
        if (cases.isEmpty() && gaps.isEmpty())
            throw new Unsupported("Command registry has no registrations");
        if (!gaps.isEmpty()) {
            analyzer.registryGaps.put("COMMAND_ARGUMENT_TYPE", List.copyOf(gaps));
            throw new Unsupported("Unresolved command argument serializers: " + gaps);
        }
        analyzer.registryGaps.remove("COMMAND_ARGUMENT_TYPE");
        String name =
                "dispatch_" + Integer.toUnsignedString((source.name() + "#network").hashCode());
        Wire key = new Wire("int", "WireCodecs.VAR_INT", "REGISTRY_COMMAND_ARGUMENT_TYPE");
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
        Wire implementation =
                new Wire(
                        "org.stackanvil.packetforge.commons.Variant<Integer>",
                        "WireCodecs.dispatch(WireCodecs.VAR_INT, java.util.Map.<Integer, PacketCodec<?>>ofEntries("
                                + entries
                                + "))",
                        "dispatch:" + name);
        analyzer.dispatches.put(
                name,
                new Dispatch(
                        name,
                        key,
                        List.copyOf(cases),
                        source.name(),
                        "argument-network-serializers",
                        implementation,
                        "COMMAND_ARGUMENT_TYPE",
                        ""));
        return new Wire(
                implementation.type(),
                "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name,
                implementation.shape());
    }

    private Wire serializer(Expression expression, SourceIndex.Source source) {
        String owner;
        if (expression instanceof MethodCallExpr factory
                && factory.getScope().isPresent()
                && Set.of("contextFree", "contextAware").contains(factory.getNameAsString())
                && factory.getArguments().size() == 1
                && factory.getArgument(0) instanceof MethodReferenceExpr
                && analyzer.index
                        .resolve(factory.getScope().get().toString(), source)
                        .equals("net.minecraft.commands.synchronization.SingletonArgumentInfo")) {
            var singleton =
                    analyzer.index.source(
                            "net.minecraft.commands.synchronization.SingletonArgumentInfo");
            var reader = method(singleton, "deserializeFromNetwork", 1);
            var writer = method(singleton, "serializeToNetwork", 2);
            if (!reader.getBody().orElseThrow().toString().equals("{\n    return this.template;\n}")
                    || !writer.getBody().orElseThrow().isEmpty())
                throw new Unsupported("Singleton command argument has network data");
            return new Wire("org.stackanvil.packetforge.commons.Unit", "WireCodecs.UNIT", "UNIT");
        } else if (expression instanceof ObjectCreationExpr created
                && created.getArguments().isEmpty()
                && created.getAnonymousClassBody().isEmpty())
            owner = analyzer.index.resolve(created.getType().getNameWithScope(), source);
        else
            throw new Unsupported(
                    "Command argument serializer is not a static singleton or constructor");
        var serializer = analyzer.index.source(owner);
        var reader = method(serializer, "deserializeFromNetwork", 1);
        var writer = method(serializer, "serializeToNetwork", 2);
        String template =
                analyzer.index.resolve(
                        reader.getType().asClassOrInterfaceType().getNameWithScope(), serializer);
        var templateSource = analyzer.index.source(template);
        if (!analyzer.index
                .resolve(
                        writer.getParameter(0)
                                .getType()
                                .asClassOrInterfaceType()
                                .getNameWithScope(),
                        serializer)
                .equals(template))
            throw new Unsupported("Argument serializer reader/writer template types differ");
        String key = template + "#argument";
        var model = analyzer.models.get(key);
        if (model == null) {
            String generated = analyzer.index.generated(template) + "_Argument";
            var lowered =
                    new ProgramLowerer(analyzer, templateSource, generated)
                            .lowerSerializer(reader, writer);
            model =
                    new Model(
                            key,
                            generated,
                            lowered.fields(),
                            List.of(),
                            false,
                            List.of(),
                            lowered.program(),
                            owner + "#network",
                            "");
            analyzer.models.put(key, model);
        }
        return modelWire(model);
    }

    private MethodDeclaration method(SourceIndex.Source source, String name, int parameters) {
        var methods =
                source.declaration().getMethodsByName(name).stream()
                        .filter(
                                m ->
                                        m.getParameters().size() == parameters
                                                && m.getBody().isPresent())
                        .toList();
        if (methods.size() != 1)
            throw new Unsupported("Ambiguous command serializer method: " + name);
        return methods.getFirst();
    }
}
