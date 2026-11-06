package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import java.util.*;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Derives public model names from source identities and distinguishable wire layouts. */
final class ModelNames {
    private final JavaParser parser =
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Map<String, String> names = new TreeMap<>();
    private final JavaTypeNames symbols;

    ModelNames(List<Model> models) {
        var families = new TreeMap<String, List<Model>>();
        for (var model : models)
            families.computeIfAbsent(base(model), ignored -> new ArrayList<>()).add(model);
        for (var family : families.entrySet()) {
            var members = family.getValue();
            if (members.size() == 1) put(members.getFirst(), family.getKey());
            else distinguish(family.getKey(), members);
        }
        var owners = new HashMap<String, String>();
        for (var entry : names.entrySet()) {
            String previous = owners.putIfAbsent(entry.getValue(), entry.getKey());
            if (previous != null && !previous.equals(entry.getKey()))
                throw new Unsupported("Public model names collide: " + previous + " and " + entry.getKey());
        }
        symbols = new JavaTypeNames(names);
    }

    Map<String, String> names() {
        return Collections.unmodifiableMap(names);
    }

    List<Model> models(List<Model> models) {
        return models.stream().map(this::model).toList();
    }

    List<Registration> registrations(List<Registration> registrations) {
        return registrations.stream()
                .map(r -> new Registration(
                        r.state(),
                        r.flow(),
                        r.id(),
                        r.name(),
                        r.originalClass(),
                        r.codecExpression(),
                        r.source(),
                        r.line(),
                        r.supported(),
                        r.reason(),
                        names.getOrDefault(r.generatedClass(), r.generatedClass()),
                        r.contextRule()))
                .toList();
    }

    List<Dispatch> dispatches(List<Dispatch> dispatches) {
        return dispatches.stream()
                .map(d -> new Dispatch(
                        d.name(),
                        wire(d.key(), symbols),
                        d.cases().stream()
                                .map(c -> new DispatchCase(c.key(), wire(c.value(), symbols), c.name()))
                                .toList(),
                        d.sourceOwner(),
                        d.originalExpression(),
                        wire(d.implementation(), symbols),
                        d.registry(),
                        d.sourceCodecField(),
                        d.fallback() == null ? null : wire(d.fallback(), symbols)))
                .toList();
    }

    private Model model(Model model) {
        var localNames = new HashMap<>(names);
        localNames.put(simple(model.generatedName()), simple(names.get(model.generatedName())));
        var local = new JavaTypeNames(localNames);
        Program program = model.program();
        if (program != null)
            program = new Program(
                    local.statements(program.decode()),
                    local.statements(program.encode()),
                    program.operations(),
                    program.helpers().stream().map(local::member).toList());
        return new Model(
                model.originalName(),
                names.get(model.generatedName()),
                model.fields().stream()
                        .map(f -> new Field(f.name(), f.originalType(), wire(f.read(), local), wire(f.write(), local)))
                        .toList(),
                model.constants(),
                model.packet(),
                model.validations().stream().map(local::statements).toList(),
                program,
                model.factory(),
                model.sourceCodecField(),
                model.decodeFailure());
    }

    private static Wire wire(Wire wire, JavaTypeNames symbols) {
        return new Wire(symbols.type(wire.type()), symbols.expression(wire.codec()), wire.shape(), wire.projection());
    }

    private void distinguish(String base, List<Model> models) {
        String owner = owner(models.getFirst());
        if (models.stream().anyMatch(m -> !owner(m).equals(owner)))
            throw new Unsupported("Different source types have the same public name: " + base);
        int count = models.getFirst().fields().size();
        if (models.stream().anyMatch(m -> m.fields().size() != count))
            throw new Unsupported("Cannot name distinct layouts with different field counts: " + base);
        var suffixes = new ArrayList<String>(Collections.nCopies(models.size(), ""));
        for (int field = 0; field < count; field++) {
            int index = field;
            if (models.stream().map(m -> m.fields().get(index)).distinct().count() == 1) continue;
            for (int i = 0; i < models.size(); i++)
                suffixes.set(
                        i,
                        suffixes.get(i)
                                + label(models.get(i).fields().get(field).read()));
            if (new HashSet<>(suffixes).size() == models.size()) {
                for (int i = 0; i < models.size(); i++) put(models.get(i), base + suffixes.get(i));
                return;
            }
        }
        throw new Unsupported("Wire layouts do not provide distinct readable names: " + base);
    }

    private String label(Wire wire) {
        if (wire.shape().startsWith("REGISTRY:"))
            return pascal(wire.shape().substring(wire.shape().lastIndexOf('.') + 1));
        Type type = parser.parseType(wire.type()).getResult().orElseThrow();
        String label = typeLabel(type);
        var codec = parser.parseExpression(wire.codec()).getResult().orElseThrow();
        if (codec instanceof MethodCallExpr call
                && call.getNameAsString().equals("string")
                && call.getArguments().size() == 1
                && call.getArgument(0).isIntegerLiteralExpr())
            label += call.getArgument(0).asIntegerLiteralExpr().getValue();
        return label;
    }

    private String typeLabel(Type type) {
        if (type.isArrayType()) return typeLabel(type.asArrayType().getComponentType()) + "Array";
        if (type.isClassOrInterfaceType()) {
            var reference = type.asClassOrInterfaceType();
            String result = pascal(reference.getNameAsString().replaceFirst("_[0-9]+$", ""));
            if (reference.getTypeArguments().isPresent())
                for (var argument : reference.getTypeArguments().get()) result += typeLabel(argument);
            return result;
        }
        if (type.isPrimitiveType()) return pascal(type.toString());
        throw new Unsupported("Cannot derive a readable layout name from " + type);
    }

    private static String base(Model model) {
        String full = model.generatedName();
        String name = simple(full);
        if (model.originalName().matches(".*#[0-9]+$")) name = name.replaceFirst("_[0-9]+$", "");
        if (!model.sourceCodecField().isEmpty()
                && !model.sourceCodecField().equals("STREAM_CODEC")
                && name.endsWith("_" + model.sourceCodecField())) {
            name = name.substring(0, name.length() - model.sourceCodecField().length() - 1);
            String variant = pascal(model.sourceCodecField().replaceFirst("_?STREAM_CODEC$", ""));
            name = name.endsWith("Packet") ? name.substring(0, name.length() - 6) + variant + "Packet" : name + variant;
        }
        return full.substring(0, full.lastIndexOf('.') + 1) + pascal(name);
    }

    private void put(Model model, String name) {
        names.put(model.generatedName(), name);
    }

    private static String owner(Model model) {
        return model.originalName().split("#", 2)[0];
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String pascal(String source) {
        var result = new StringBuilder();
        for (String word : source.split("_")) {
            if (word.isEmpty()) continue;
            if (word.equals(word.toUpperCase(Locale.ROOT))) word = word.toLowerCase(Locale.ROOT);
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
