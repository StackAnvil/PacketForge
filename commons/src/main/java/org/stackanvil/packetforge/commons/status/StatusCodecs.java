package org.stackanvil.packetforge.commons.status;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.stackanvil.packetforge.commons.PacketCodec;
import org.stackanvil.packetforge.commons.WireCodecs;
import org.stackanvil.packetforge.commons.text.TextCodecs;

/** Server-list JSON with the source codec's lenient optional fields and defaults. */
public final class StatusCodecs {
    private static final String ICON_PREFIX = "data:image/png;base64,";

    private StatusCodecs() {}

    public static PacketCodec<ServerStatus> status(PacketCodec<JsonElement> json) {
        return WireCodecs.mapped(json, StatusCodecs::read, StatusCodecs::write);
    }

    private static ServerStatus read(JsonElement value) {
        if (!(value instanceof JsonObject object))
            throw new IllegalArgumentException("Expected a server status object");
        return new ServerStatus(
                optional(object, "description", TextCodecs::decodeJson).orElse(Component.empty()),
                optional(object, "players", StatusCodecs::players),
                optional(object, "version", v -> {
                    var version = v.getAsJsonObject();
                    return new ServerStatus.Version(string(version.get("name")), number(version.get("protocol")));
                }),
                optional(object, "favicon", v -> {
                    String encoded = string(v);
                    if (!encoded.startsWith(ICON_PREFIX)) throw new IllegalArgumentException("Unknown icon format");
                    return new ServerStatus.Favicon(Base64.getDecoder()
                            .decode(encoded.substring(ICON_PREFIX.length()).replace("\n", "")));
                }),
                optional(object, "enforcesSecureChat", StatusCodecs::bool).orElse(false));
    }

    private static ServerStatus.Players players(JsonElement value) {
        var players = value.getAsJsonObject();
        int max = number(players.get("max")), online = number(players.get("online"));
        List<ServerStatus.Player> sample = optional(players, "sample", v -> {
                    var list = new ArrayList<ServerStatus.Player>();
                    for (var entry : v.getAsJsonArray()) {
                        var player = entry.getAsJsonObject();
                        list.add(new ServerStatus.Player(
                                UUID.fromString(string(player.get("id"))), string(player.get("name"))));
                    }
                    return List.copyOf(list);
                })
                .orElse(List.of());
        return new ServerStatus.Players(max, online, sample);
    }

    private static JsonElement write(ServerStatus value) {
        var json = new JsonObject();
        if (!value.description().equals(Component.empty()))
            json.add("description", TextCodecs.encodeJson(value.description()));
        value.players().ifPresent(v -> {
            var players = new JsonObject();
            players.addProperty("max", v.max());
            players.addProperty("online", v.online());
            if (!v.sample().isEmpty()) {
                var sample = new JsonArray();
                for (var entry : v.sample()) {
                    var player = new JsonObject();
                    player.addProperty("id", entry.id().toString());
                    player.addProperty("name", entry.name());
                    sample.add(player);
                }
                players.add("sample", sample);
            }
            json.add("players", players);
        });
        value.version().ifPresent(v -> {
            var version = new JsonObject();
            version.addProperty("name", v.name());
            version.addProperty("protocol", v.protocol());
            json.add("version", version);
        });
        value.favicon()
                .ifPresent(v -> json.addProperty(
                        "favicon", ICON_PREFIX + Base64.getEncoder().encodeToString(v.iconBytes())));
        if (value.enforcesSecureChat()) json.addProperty("enforcesSecureChat", true);
        return json;
    }

    private static <T> Optional<T> optional(JsonObject object, String name, Function<JsonElement, T> read) {
        var value = object.get(name);
        if (value == null || value.isJsonNull()) return Optional.empty();
        try {
            return Optional.of(read.apply(value));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String string(JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) return primitive.getAsString();
        throw new IllegalArgumentException("Expected a string");
    }

    private static int number(JsonElement value) {
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isNumber()) return primitive.getAsNumber().intValue();
        }
        throw new IllegalArgumentException("Expected a number");
    }

    private static boolean bool(JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) return primitive.getAsBoolean();
        throw new IllegalArgumentException("Expected a boolean");
    }
}
