package org.stackanvil.packetforge.commons.text;

import com.google.gson.JsonObject;
import java.util.Objects;
import net.kyori.adventure.dialog.DialogLike;

/** Inline dialog data for a JSON text stream. Adventure leaves dialog construction to the platform. */
public record JsonDialog(JsonObject value) implements DialogLike {
    public JsonDialog {
        value = Objects.requireNonNull(value).deepCopy();
    }

    @Override
    public JsonObject value() {
        return value.deepCopy();
    }
}
