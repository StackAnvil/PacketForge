package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.*;
import java.util.regex.Pattern;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Lowers recovered wire schemas to the public API after source dependency validation. */
final class PayloadApi {
    record PayloadType(String javaType, Map<String, String> cases, String fallbackType) {
        PayloadType {
            cases = Collections.unmodifiableMap(new LinkedHashMap<>(cases));
        }
    }

    private static final String COMMONS = "org.stackanvil.packetforge.commons.";
    private static final String PACKAGE = "org.stackanvil.packetforge.protocol.payload.";
    private static final Pattern DISPATCH = Pattern.compile("dispatch:(dispatch_[0-9]+)");
    private final JavaParser parser =
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Map<String, Dispatch> tables = new LinkedHashMap<>();
    private final Map<String, PayloadType> types = new LinkedHashMap<>();

    PayloadApi(List<Dispatch> dispatches) {
        var unique = new HashSet<String>();
        for (var table : dispatches) {
            tables.put(table.name(), table);
            var names = new LinkedHashMap<String, String>();
            if (table.registry().equals("DATA_COMPONENT_TYPE")) {
                types.put(table.name(), new PayloadType(COMMONS + "DataComponent<?>", Map.of(), ""));
                continue;
            }
            String name = unionName(table);
            if (!unique.add(name)) throw new Unsupported("Payload names collide: " + name);
            var cases = new HashSet<String>();
            for (var branch : table.cases()) {
                String local = javaName((branch.key().startsWith("left:")
                                ? "left_"
                                : branch.key().startsWith("right:") ? "right_" : "")
                        + (table.key().shape().startsWith("enum:") ? branch.key() : branch.name())
                                .replaceFirst("^minecraft:", ""));
                if (!cases.add(local) || table.fallback() != null && local.equals("Unknown"))
                    throw new Unsupported("Payload case names collide: " + name + "." + local);
                names.put(branch.key(), name + "." + local);
            }
            types.put(table.name(), new PayloadType(name, names, table.fallback() == null ? "" : name + ".Unknown"));
        }
    }

    Map<String, PayloadType> types() {
        return Collections.unmodifiableMap(types);
    }

    String type(String table) {
        var definition = types.get(table);
        if (definition == null) throw new Unsupported("Unresolved public payload: " + table);
        return definition.javaType();
    }

    String caseType(String table, String key) {
        return types.get(table).cases().get(key);
    }

    List<Model> models(List<Model> models) {
        return models.stream().map(this::model).toList();
    }

    List<Dispatch> dispatches() {
        return tables.values().stream()
                .map(table -> new Dispatch(
                        table.name(),
                        table.key(),
                        table.cases().stream()
                                .map(branch -> new DispatchCase(branch.key(), wire(branch.value()), branch.name()))
                                .toList(),
                        table.sourceOwner(),
                        table.originalExpression(),
                        new Wire(
                                type(table.name()),
                                table.registry().equals("DATA_COMPONENT_TYPE")
                                        ? "ComponentCodecs.component(DataComponentTypes.values())"
                                        : type(table.name()) + ".CODEC",
                                table.implementation().shape()),
                        table.registry(),
                        table.sourceCodecField(),
                        table.fallback() == null ? null : wire(table.fallback())))
                .toList();
    }

    private Model model(Model model) {
        var fields = model.fields().stream()
                .map(field -> new Field(field.name(), field.originalType(), wire(field.read()), wire(field.write())))
                .toList();
        Program program = model.program();
        if (program != null && !fields.equals(model.fields())) {
            var block =
                    parser.parseBlock("{" + program.decode() + "}").getResult().orElseThrow();
            for (var variable : block.findAll(VariableDeclarator.class)) {
                for (int i = 0; i < fields.size(); i++) {
                    var original = model.fields().get(i);
                    if (variable.getNameAsString().equals("$packetforge_" + original.name())
                            && variable.getTypeAsString().equals(original.read().type()))
                        variable.setType(fields.get(i).read().type());
                }
            }
            String decode = String.join(
                    "\n", block.getStatements().stream().map(Object::toString).toList());
            program = new Program(decode, program.encode(), program.operations(), program.helpers());
        }
        return new Model(
                model.originalName(),
                model.generatedName(),
                fields,
                model.constants(),
                model.packet(),
                model.validations(),
                program,
                model.factory(),
                model.sourceCodecField(),
                model.decodeFailure());
    }

    private Wire wire(Wire wire) {
        var matcher = DISPATCH.matcher(wire.shape());
        var dispatches = new ArrayList<String>();
        while (matcher.find()) dispatches.add(matcher.group(1));
        if (dispatches.isEmpty()) return wire;
        var javaType = parser.parseType(wire.type()).getResult().orElseThrow();
        var candidates = javaType.findAll(ClassOrInterfaceType.class).stream()
                .filter(t -> Set.of(
                                COMMONS + "Variant",
                                COMMONS + "MetadataEntry",
                                COMMONS + "CommandNode",
                                COMMONS + "ActionEntry")
                        .contains(t.getNameWithScope()))
                .toList();
        if (candidates.size() != dispatches.size())
            throw new Unsupported("Cannot bind payload types to wire shape: " + wire);
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            String table = dispatches.get(i), payload = type(table);
            String replacement = switch (candidate.getNameAsString()) {
                case "Variant" -> payload;
                case "MetadataEntry" -> COMMONS + "MetadataEntry<" + payload + ">";
                case "CommandNode" -> COMMONS + "CommandNode<" + payload + ">";
                case "ActionEntry" ->
                    COMMONS + "ActionEntry<" + tables.get(table).key().type() + ", " + payload + ">";
                default -> throw new AssertionError(candidate);
            };
            var changed = parser.parseType(replacement).getResult().orElseThrow();
            if (candidate == javaType) javaType = changed;
            else candidate.replace(changed);
        }
        return new Wire(javaType.toString(), wire.codec(), wire.shape(), wire.projection());
    }

    private static String unionName(Dispatch table) {
        var segments = Arrays.asList(table.sourceOwner().split("\\."));
        int start = 0;
        while (start < segments.size()
                && !Character.isUpperCase(segments.get(start).charAt(0))) start++;
        String owner = String.join(
                "",
                segments.subList(start, segments.size()).stream()
                        .map(segment -> segment.replaceFirst("Packet$", ""))
                        .toList());
        String variant = table.sourceCodecField().replaceFirst("_?STREAM_CODEC$", "");
        return PACKAGE
                + owner
                + (variant.isEmpty() ? "" : javaName(variant))
                + (owner.endsWith("Payload") ? "" : "Payload");
    }

    private static String javaName(String source) {
        StringBuilder result = new StringBuilder();
        for (String word : source.split("[^a-zA-Z0-9]+")) {
            if (word.isEmpty()) continue;
            if (word.equals(word.toUpperCase(Locale.ROOT))) word = word.toLowerCase(Locale.ROOT);
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        if (result.isEmpty()) throw new Unsupported("Empty payload name: " + source);
        if (!Character.isJavaIdentifierStart(result.charAt(0))) result.insert(0, "Case");
        return result.toString();
    }
}
