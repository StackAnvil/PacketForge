package org.stackanvil.packetforge.commons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public final class JsonCodecs {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonCodecs() {}

    public static PacketCodec<JsonElement> json(int maxChars) {
        return WireCodecs.mapped(WireCodecs.string(maxChars), JsonParser::parseString, GSON::toJson);
    }
}
