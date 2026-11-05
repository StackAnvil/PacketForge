package org.stackanvil.packetforge.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedRoundTripTest {
    @Test void everyGeneratedRegistrationRoundTripsBodiesAndNumericIds() throws Exception {
        Map<String, JsonObject> models = models();
        PacketRegistry registry = GeneratedProtocol.registry();
        for (var entry : registry.entries().values()) for (int variant = 0; variant < 3; variant++) {
            Packet packet = (Packet) sample(entry.type(), "", variant, models);
            ByteBuf buffer = Unpooled.buffer();
            ByteBuf reencoded = Unpooled.buffer();
            try {
                registry.encode(entry.key(), packet, buffer);
                byte[] expected = new byte[buffer.readableBytes()]; buffer.getBytes(0, expected);
                Packet decoded = registry.decode(entry.key().state(), entry.key().flow(), buffer);
                registry.encode(entry.key(), decoded, reencoded);
                byte[] actual = new byte[reencoded.readableBytes()]; reencoded.readBytes(actual);
                assertArrayEquals(expected, actual, entry.name() + " variant " + variant);
            } finally { buffer.release(); reencoded.release(); }
        }
    }
    static Map<String, JsonObject> models() throws Exception {
        Map<String, JsonObject> models = new HashMap<>();
        try (var stream = GeneratedProtocol.class.getResourceAsStream("/packetforge/protocol.json")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                var report = JsonParser.parseReader(reader).getAsJsonObject();
                for (var model : report.getAsJsonArray("models")) { var object = model.getAsJsonObject(); models.put(object.get("generatedName").getAsString(), object); }
            }
        }
        return models;
    }
    static Object sample(Type type, String shape, int variant, Map<String, JsonObject> models) throws Exception {
        if (shape.startsWith("nullable<")) {
            if (variant == 0) return null;
            shape = shape.substring("nullable<".length(), shape.length() - 1);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type inner = parameterized.getActualTypeArguments()[0];
            String innerShape = shape.contains("<") ? shape.substring(shape.indexOf('<') + 1, shape.length() - 1) : "";
            if (parameterized.getRawType() == Optional.class) return variant == 0 ? Optional.empty() : Optional.of(sample(inner, innerShape, variant, models));
            if (parameterized.getRawType() == List.class) return variant == 0 ? List.of() : List.of(sample(inner, innerShape, variant, models));
            throw new IllegalArgumentException("Unrecognized generic type: " + type);
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == String.class) return shape.equals("IDENTIFIER") ? "minecraft:stone" : variant == 0 ? "" : "é";
        if (clazz == int.class || clazz == Integer.class) return variant;
        if (clazz == long.class || clazz == Long.class) return variant == 2 ? Long.MIN_VALUE : (long) variant;
        if (clazz == float.class || clazz == Float.class) return (float) variant;
        if (clazz == double.class || clazz == Double.class) return (double) variant;
        if (clazz == byte.class || clazz == Byte.class) return (byte) variant;
        if (clazz == short.class || clazz == Short.class) return (short) variant;
        if (clazz == boolean.class || clazz == Boolean.class) return variant != 0;
        if (clazz == byte[].class) return variant == 0 ? new byte[0] : new byte[]{1, -1};
        if (clazz == UUID.class) return new UUID(variant, -variant);
        if (clazz == BlockPosition.class) return new BlockPosition(-variant, variant, variant);
        if (clazz.isEnum()) { Object[] values = clazz.getEnumConstants(); return values[variant % values.length]; }
        if (clazz.isRecord()) {
            var fields = models.get(clazz.getName()).getAsJsonArray("fields");
            var components = clazz.getRecordComponents();
            Class<?>[] parameters = new Class<?>[components.length]; Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                parameters[i] = components[i].getType();
                values[i] = sample(components[i].getGenericType(), fields.get(i).getAsJsonObject().getAsJsonObject("read").get("shape").getAsString(), variant, models);
            }
            return clazz.getConstructor(parameters).newInstance(values);
        }
        throw new IllegalArgumentException("No sample for " + clazz);
    }
}
