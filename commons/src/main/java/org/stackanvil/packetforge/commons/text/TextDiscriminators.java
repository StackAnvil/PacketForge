package org.stackanvil.packetforge.commons.text;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.cloudburstmc.nbt.NbtMap;

/** Explicit source discriminators take precedence over legacy fuzzy text fields. */
final class TextDiscriminators {
    static final Map<String, List<String>> COMPONENTS = Map.of(
            "text",
            List.of("text"),
            "translatable",
            List.of("translate"),
            "keybind",
            List.of("keybind"),
            "score",
            List.of("score"),
            "selector",
            List.of("selector"),
            "nbt",
            List.of("nbt"),
            "object",
            List.of("sprite", "player"));
    static final Map<String, List<String>> SOURCES =
            Map.of("entity", List.of("entity"), "block", List.of("block"), "storage", List.of("storage"));
    static final Map<String, List<String>> OBJECTS = Map.of("atlas", List.of("sprite"), "player", List.of("player"));

    private TextDiscriminators() {}

    static NbtMap select(NbtMap map, String discriminator, Map<String, List<String>> fields) {
        if (!map.containsKey(discriminator)) return map;
        if (!(map.get(discriminator) instanceof String type))
            throw new IllegalArgumentException("Expected text discriminator string: " + discriminator);
        var selected = fields(fields, type);
        var result = map.toBuilder();
        fields.values().stream()
                .flatMap(Collection::stream)
                .filter(field -> !selected.contains(field))
                .forEach(result::remove);
        return result.build();
    }

    static JsonObject select(JsonObject map, String discriminator, Map<String, List<String>> fields) {
        if (!map.has(discriminator)) return map;
        if (!(map.get(discriminator) instanceof com.google.gson.JsonPrimitive type) || !type.isString())
            throw new IllegalArgumentException("Expected text discriminator string: " + discriminator);
        var selected = fields(fields, type.getAsString());
        var result = map.deepCopy();
        fields.values().stream()
                .flatMap(Collection::stream)
                .filter(field -> !selected.contains(field))
                .forEach(result::remove);
        return result;
    }

    private static List<String> fields(Map<String, List<String>> fields, String type) {
        var selected = fields.get(type);
        if (selected == null) throw new IllegalArgumentException("Unknown text discriminator: " + type);
        return selected;
    }
}
