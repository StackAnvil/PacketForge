package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.Type;
import java.util.*;
import java.util.regex.Pattern;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Replaces physical component registry IDs with the canonical typed keys in public value fields. */
final class ComponentReferences {
    private static final Pattern REFERENCE = Pattern.compile("REGISTRY:(?:[A-Za-z0-9_]+\\.)*DATA_COMPONENT_TYPE\\b");
    private final JavaParser parser =
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final String table;

    ComponentReferences(List<Dispatch> tables) {
        table = tables.stream()
                .filter(d -> d.registry().equals("DATA_COMPONENT_TYPE"))
                .map(Dispatch::name)
                .findFirst()
                .orElse("");
    }

    static boolean uses(String shape) {
        return REFERENCE.matcher(shape).find();
    }

    List<Model> models(List<Model> models) {
        return models.stream().map(this::model).toList();
    }

    private Model model(Model model) {
        var fields = model.fields().stream()
                .map(field -> new Field(field.name(), field.originalType(), wire(field.read()), wire(field.write())))
                .toList();
        if (model.program() != null && !fields.equals(model.fields()))
            throw new Unsupported("Unknown imperative component reference layout: " + model.originalName());
        return new Model(
                model.originalName(),
                model.generatedName(),
                fields,
                model.constants(),
                model.packet(),
                model.validations(),
                model.program(),
                model.factory(),
                model.sourceCodecField(),
                model.decodeFailure());
    }

    List<Dispatch> dispatches(List<Dispatch> tables) {
        return tables.stream()
                .map(d -> new Dispatch(
                        d.name(),
                        d.key(),
                        d.cases().stream()
                                .map(c -> new DispatchCase(c.key(), wire(c.value()), c.name()))
                                .toList(),
                        d.sourceOwner(),
                        d.originalExpression(),
                        d.implementation(),
                        d.registry(),
                        d.sourceCodecField(),
                        d.fallback()))
                .toList();
    }

    private Wire wire(Wire wire) {
        if (!uses(wire.shape())) return wire;
        if (table.isEmpty())
            throw new Unsupported("Unresolved component registry for public reference: " + wire.shape());
        var value = lower(
                parser.parseType(wire.type()).getResult().orElseThrow(),
                parser.parseExpression(wire.codec()).getResult().orElseThrow(),
                wire.shape());
        return new Wire(value.type().toString(), value.codec().toString(), wire.shape(), wire.projection());
    }

    private record Value(Type type, Expression codec) {}

    private Value lower(Type type, Expression codec, String shape) {
        if (!uses(shape)) return new Value(type, codec);
        if (REFERENCE.matcher(shape).matches()) {
            if (!Set.of("int", "Integer").contains(type.toString())
                    || !codec.toString().equals("WireCodecs.VAR_INT"))
                throw new Unsupported("Unknown component registry reference: " + shape);
            return new Value(
                    parser.parseType("org.stackanvil.packetforge.commons.DataComponentType<?>")
                            .getResult()
                            .orElseThrow(),
                    parser.parseExpression(
                                    "ComponentCodecs.type(org.stackanvil.packetforge.protocol.GeneratedWireCodecs."
                                            + table + "_types())")
                            .getResult()
                            .orElseThrow());
        }
        int start = shape.indexOf('<');
        if (start < 0 || !shape.endsWith(">") || !type.isClassOrInterfaceType() || !codec.isMethodCallExpr())
            throw new Unsupported("Unknown component reference container: " + shape);
        String kind = shape.substring(0, start);
        if (!Set.of("map", "set", "list", "optional").contains(kind))
            throw new Unsupported("Unknown component reference wrapper: " + kind);
        var call = codec.asMethodCallExpr();
        if (!call.getNameAsString().equals(kind)
                || !call.getScope().map(Object::toString).orElse("").equals("WireCodecs"))
            throw new Unsupported("Component reference codec does not match its shape: " + shape);
        var shapes = children(shape.substring(start + 1, shape.length() - 1));
        var arguments = type.asClassOrInterfaceType().getTypeArguments().orElseThrow();
        if (arguments.size() != shapes.size() || call.getArguments().size() < shapes.size())
            throw new Unsupported("Component reference arity differs: " + shape);
        for (int i = 0; i < shapes.size(); i++) {
            var child = lower(arguments.get(i), call.getArgument(i), shapes.get(i));
            arguments.set(i, child.type());
            call.setArgument(i, child.codec());
        }
        return new Value(type, codec);
    }

    private static List<String> children(String shape) {
        var result = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < shape.length(); i++) {
            if (shape.charAt(i) == '<') depth++;
            else if (shape.charAt(i) == '>') depth--;
            else if (shape.charAt(i) == ',' && depth == 0) {
                result.add(shape.substring(start, i));
                start = i + 1;
            }
        }
        result.add(shape.substring(start));
        return result;
    }
}
