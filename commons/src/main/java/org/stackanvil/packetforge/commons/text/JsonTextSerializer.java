package org.stackanvil.packetforge.commons.text;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Modern JSON text, including event payloads that Adventure's default Gson serializer omits. */
final class JsonTextSerializer {
    private JsonTextSerializer() {}

    private static final Gson GSON = GsonComponentSerializer.gson()
            .serializer()
            .newBuilder()
            .registerTypeAdapterFactory(new EventStyles())
            .registerTypeAdapter(BlockNBTComponent.Pos.class, new Positions().nullSafe())
            .create();

    private static final class Positions extends TypeAdapter<BlockNBTComponent.Pos> {
        public BlockNBTComponent.Pos read(JsonReader input) throws IOException {
            return NbtUtil.deserializeBlockPos(input.nextString());
        }

        public void write(JsonWriter output, BlockNBTComponent.Pos value) throws IOException {
            output.value(value.asString());
        }
    }

    static Component read(JsonElement value) {
        return GSON.fromJson(normalize(value), Component.class);
    }

    static JsonElement write(Component value) {
        return normalize(GSON.toJsonTree(value, Component.class));
    }

    static Style readStyle(JsonElement value) {
        return GSON.fromJson(value, Style.class);
    }

    static JsonElement writeStyle(Style value) {
        return GSON.toJsonTree(value, Style.class);
    }

    private static JsonElement normalize(JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) return value;
        if (value instanceof JsonArray list && !list.isEmpty()) {
            var result = new JsonArray();
            list.forEach(element -> result.add(normalize(element)));
            return result;
        }
        if (value instanceof JsonObject object) {
            object = TextDiscriminators.select(object, "type", TextDiscriminators.COMPONENTS)
                    .deepCopy();
            if (object.has("nbt")) {
                object = TextDiscriminators.select(object, "source", TextDiscriminators.SOURCES);
                for (String field : java.util.List.of("interpret", "plain"))
                    if (object.has(field)
                            && (!(object.get(field) instanceof JsonPrimitive flag)
                                    || !flag.isBoolean()
                                    || !flag.getAsBoolean())) object.remove(field);
                if (object.has("interpret")
                        && object.get("interpret").getAsBoolean()
                        && object.has("plain")
                        && object.get("plain").getAsBoolean())
                    throw new JsonParseException("NBT text cannot both interpret and display plain values");
            }
            if (object.has("sprite") || object.has("player"))
                object = TextDiscriminators.select(object, "object", TextDiscriminators.OBJECTS);
            if (object.has("extra")) {
                var extra = object.getAsJsonArray("extra");
                if (extra.isEmpty()) throw new JsonParseException("Text extras must not be empty");
                var normalized = new JsonArray();
                extra.forEach(element -> normalized.add(normalize(element)));
                object.add("extra", normalized);
            }
            if (object.has("with")) {
                var arguments = new JsonArray();
                for (var argument : object.getAsJsonArray("with"))
                    arguments.add(
                            argument instanceof JsonObject || argument instanceof JsonArray
                                    ? normalize(argument)
                                    : argument);
                object.add("with", arguments);
            }
            if (object.has("separator") && !object.get("separator").isJsonNull())
                object.add("separator", normalize(object.get("separator")));
            if ((object.has("sprite") || object.has("player")) && object.has("fallback"))
                object.add("fallback", normalize(object.get("fallback")));
            if (object.has("hover_event")) {
                var hover = object.getAsJsonObject("hover_event");
                String action = hover.get("action").getAsString();
                if (action.equals("show_item")
                        && hover.get("count") instanceof JsonPrimitive count
                        && count.isNumber()
                        && count.getAsInt() == 1) hover.remove("count");
                if (action.equals("show_text")) hover.add("value", normalize(hover.get("value")));
                if (action.equals("show_entity") && hover.has("name")) hover.add("name", normalize(hover.get("name")));
            }
            return object;
        }
        throw new JsonParseException("Expected a text string, nonempty component list, or component object");
    }

    private static final class EventStyles implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != Style.class) return null;
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            TypeAdapter<JsonElement> trees = gson.getAdapter(JsonElement.class);
            return new TypeAdapter<>() {
                @Override
                public T read(JsonReader input) throws IOException {
                    JsonObject tree = trees.read(input).getAsJsonObject();
                    ClickEvent<?> replacement = null;
                    if (tree.has("click_event")) {
                        var event = tree.getAsJsonObject("click_event");
                        String action = event.get("action").getAsString();
                        if (action.equals("open_file"))
                            throw new JsonParseException("Open-file click events are not allowed in protocol text");
                        if (action.equals("show_dialog")) {
                            var dialog = event.get("dialog");
                            replacement = dialog instanceof JsonPrimitive reference && reference.isString()
                                    ? ClickEvent.showDialog(new NbtDialog(Key.key(reference.getAsString())))
                                    : ClickEvent.showDialog(new JsonDialog(dialog.getAsJsonObject()));
                            tree = tree.deepCopy();
                            tree.remove("click_event");
                        } else if (action.equals("custom") && event.has("payload")) {
                            tree = tree.deepCopy();
                            tree.getAsJsonObject("click_event")
                                    .addProperty(
                                            "payload",
                                            TextNbt.toSnbt(TextNbt.toValue(TextJson.toNbt(event.get("payload")))));
                        }
                    }
                    T result = delegate.fromJsonTree(tree);
                    if (replacement == null) return result;
                    @SuppressWarnings("unchecked")
                    T replaced = (T) ((Style) result).clickEvent(replacement);
                    return replaced;
                }

                @Override
                public void write(JsonWriter output, T value) throws IOException {
                    Style style = (Style) value;
                    var click = style.clickEvent();
                    if (click != null && click.action() == ClickEvent.Action.OPEN_FILE)
                        throw new JsonParseException("Open-file click events are not allowed in protocol text");
                    boolean dialog = click != null && click.action() == ClickEvent.Action.SHOW_DIALOG;
                    @SuppressWarnings("unchecked")
                    T ordinary = dialog ? (T) style.clickEvent(null) : value;
                    JsonObject tree = delegate.toJsonTree(ordinary).getAsJsonObject();
                    if (dialog) {
                        var event = new JsonObject();
                        event.addProperty("action", "show_dialog");
                        var payload = (ClickEvent.Payload.Dialog) click.payload();
                        if (payload.dialog() instanceof JsonDialog inline) event.add("dialog", inline.value());
                        else if (payload.dialog() instanceof NbtDialog reference
                                && reference.reference().isPresent())
                            event.addProperty(
                                    "dialog",
                                    reference.reference().orElseThrow().asString());
                        else throw new JsonParseException("JSON inline dialogs require JsonDialog data");
                        tree.add("click_event", event);
                    } else if (click != null
                            && click.payload() instanceof ClickEvent.Payload.Custom custom
                            && custom.nbt() != null) {
                        tree.getAsJsonObject("click_event")
                                .add(
                                        "payload",
                                        TextJson.fromNbt(TextNbt.fromValue(
                                                TextNbt.fromSnbt(custom.nbt().string()))));
                    }
                    trees.write(output, tree);
                }
            };
        }
    }
}
