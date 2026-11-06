package org.stackanvil.packetforge.generator;

import java.util.*;
import org.stackanvil.packetforge.generator.Analyzer.Field;
import org.stackanvil.packetforge.generator.Analyzer.Model;

/** Constructor parameter sets that retain the canonical record's validation and field order. */
final class RecordConstruction {
    private static final Map<String, String> EMPTY = Map.of(
            "java.util.Optional", "java.util.Optional.empty()",
            "java.util.OptionalInt", "java.util.OptionalInt.empty()",
            "java.util.OptionalLong", "java.util.OptionalLong.empty()",
            "java.util.OptionalDouble", "java.util.OptionalDouble.empty()");

    private RecordConstruction() {}

    static List<Field> required(List<Field> fields) {
        return fields.stream()
                .filter(field -> !EMPTY.containsKey(erasure(field.read().type())))
                .toList();
    }

    static List<List<Field>> parameterSets(Model model) {
        var required = required(model.fields());
        return required.size() == model.fields().size() ? List.of(model.fields()) : List.of(model.fields(), required);
    }

    static String arguments(Model model, List<Field> supplied) {
        Set<String> names = new HashSet<>();
        supplied.forEach(field -> names.add(field.name()));
        return String.join(
                ", ",
                model.fields().stream()
                        .map(field -> names.contains(field.name())
                                ? field.name()
                                : EMPTY.get(erasure(field.read().type())))
                        .toList());
    }

    static boolean matchesCanonicalPayload(List<Field> fields, String payloadType) {
        return fields.size() == 1 && erasure(fields.getFirst().read().type()).equals(erasure(payloadType));
    }

    private static String erasure(String type) {
        int arguments = type.indexOf('<');
        return arguments < 0 ? type : type.substring(0, arguments) + type.substring(type.lastIndexOf('>') + 1);
    }
}
