package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.ParameterizedType;
import java.util.HashSet;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.payload.ServerboundCustomPayload;

class PayloadApiTest {
    @Test
    void everyRecoveredDispatchExposesSealedCasesOrTypedComponents() throws Exception {
        var models = GeneratedRoundTripTest.models();
        int sealed = 0, components = 0;
        for (var entry : models.entrySet()) {
            if (!entry.getKey().startsWith("$dispatch:")) continue;
            var dispatch = entry.getValue();
            var api = models.get("$payload:" + dispatch.get("name").getAsString());
            var codecField =
                    GeneratedWireCodecs.class.getField(dispatch.get("name").getAsString());
            var codecType = (ParameterizedType) codecField.getGenericType();
            String name = api.get("javaType").getAsString();
            if (dispatch.get("registry").getAsString().equals("DATA_COMPONENT_TYPE")) {
                var componentType =
                        assertInstanceOf(ParameterizedType.class, codecType.getActualTypeArguments()[0]);
                assertEquals(DataComponent.class, componentType.getRawType());
                components++;
                continue;
            }
            var type = (Class<?>) GeneratedRoundTripTest.type(name);
            assertEquals(type, codecType.getActualTypeArguments()[0]);
            assertTrue(type.isSealed(), name);
            assertTrue(TypedPayload.class.isAssignableFrom(type), name);
            assertEquals(
                    dispatch.getAsJsonArray("cases").size() + (dispatch.has("fallback") ? 1 : 0),
                    type.getPermittedSubclasses().length);
            var names = new HashSet<String>();
            for (var candidate : type.getPermittedSubclasses()) {
                assertTrue(candidate.isRecord());
                names.add(candidate.getCanonicalName());
                for (var component : candidate.getRecordComponents())
                    assertNotEquals(Object.class, component.getType());
            }
            for (var branch : dispatch.getAsJsonArray("cases"))
                assertTrue(names.contains(api.getAsJsonObject("cases")
                        .get(branch.getAsJsonObject().get("key").getAsString())
                        .getAsString()));
            sealed++;
        }
        assertEquals(21, sealed);
        assertEquals(1, components);
    }

    @Test
    void unknownCustomPayloadsCannotClaimAKnownChannel() {
        var value = new ServerboundCustomPayload.Unknown(Key.key("test:custom"), new byte[] {1, 2});
        assertEquals(Key.key("test:custom"), value.type());
        assertArrayEquals(new byte[] {1, 2}, value.data());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerboundCustomPayload.Unknown(Key.key("minecraft:brand"), new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerboundCustomPayload.Unknown(Key.key("", "brand"), new byte[0]));
    }
}
