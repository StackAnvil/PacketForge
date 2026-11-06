package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.PacketCodec;
import org.stackanvil.packetforge.protocol.packet.common.ClientboundCustomPayloadConfigPacket;
import org.stackanvil.packetforge.protocol.payload.*;
import org.stackanvil.packetforge.protocol.type.core.particles.DustParticleOptions;
import org.stackanvil.packetforge.protocol.type.server.network.*;
import org.stackanvil.packetforge.protocol.type.world.item.crafting.display.SlotDisplayItemSlotDisplay;

class ErgonomicsTest {
    @Test
    void payloadFactoriesAndConstructorsProduceTheCanonicalWireValues() {
        var display = SlotDisplayPayload.item(1);
        assertEquals(new SlotDisplayPayload.Item(new SlotDisplayItemSlotDisplay(1)), display);
        assertEquals(display, new SlotDisplayPayload.Item(1));
        roundTrip(SlotDisplayPayload.CODEC, display);
        var dust = ParticleTypesPayload.dust(0xff123456, 1.5f);
        assertEquals(new ParticleTypesPayload.Dust(new DustParticleOptions(0xff123456, 1.5f)), dust);
        assertEquals(dust, new ParticleTypesPayload.Dust(0xff123456, 1.5f));
        roundTrip(ParticleTypesPayload.CODEC, dust);
        var packet = new ClientboundCustomPayloadConfigPacket(ClientboundCustomPayloadConfig.brand("PacketForge"));
        roundTrip(ClientboundCustomPayloadConfigPacket.CODEC, packet);
        roundTrip(ArgumentTypeInfosPayload.CODEC, ArgumentTypeInfosPayload.brigadierBool());
        roundTrip(EntityDataSerializersPayload.CODEC, EntityDataSerializersPayload.intValue(123));
    }

    @Test
    void convenientConstructionRetainsTheSourceValidation() {
        var ingredient = SlotDisplayPayload.item(1);
        var empty = SlotDisplayPayload.empty();
        var valid = RecipeDisplayPayload.craftingShaped(1, 1, List.of(ingredient), ingredient, empty);
        roundTrip(RecipeDisplayPayload.CODEC, valid);
        var factoryFailure = assertThrows(
                IllegalArgumentException.class,
                () -> RecipeDisplayPayload.craftingShaped(2, 1, List.of(ingredient), ingredient, empty));
        var constructorFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new RecipeDisplayPayload.CraftingShaped(2, 1, List.of(ingredient), ingredient, empty));
        assertEquals("Invalid shaped recipe display contents", factoryFailure.getMessage());
        assertEquals(factoryFailure.getMessage(), constructorFailure.getMessage());
    }

    @Test
    void everyKnownPayloadCaseHasAFactoryThatRetainsItsWireEncoding() throws Exception {
        var models = GeneratedRoundTripTest.models();
        int cases = 0;
        for (var entry : models.entrySet()) {
            if (!entry.getKey().startsWith("$dispatch:")) continue;
            var table = entry.getValue();
            if (table.get("registry").getAsString().equals("DATA_COMPONENT_TYPE")) continue;
            var owner = (Class<?>) GeneratedRoundTripTest.type(
                    table.getAsJsonObject("implementation").get("type").getAsString());
            for (var candidate : table.getAsJsonArray("cases")) {
                var branch = candidate.getAsJsonObject();
                var schema = branch.getAsJsonObject("value");
                Object value = GeneratedRoundTripTest.sample(
                        GeneratedRoundTripTest.type(schema.get("type").getAsString()),
                        schema.get("shape").getAsString(),
                        1,
                        models);
                Object canonical =
                        GeneratedRoundTripTest.payload(table, branch.get("key").getAsString(), value, models);
                var factory = Arrays.stream(owner.getDeclaredMethods())
                        .filter(method -> Modifier.isStatic(method.getModifiers())
                                && method.getReturnType() == canonical.getClass())
                        .max(Comparator.comparingInt(java.lang.reflect.Method::getParameterCount))
                        .orElseThrow();
                Object[] arguments;
                if (canonical.getClass().getRecordComponents().length == 0) arguments = new Object[0];
                else if (factory.getParameterCount() == 1 && factory.getParameterTypes()[0] == value.getClass())
                    arguments = new Object[] {value};
                else if (value.getClass().isRecord()
                        && models.containsKey(schema.get("type").getAsString())) {
                    var fields = value.getClass().getRecordComponents();
                    arguments = new Object[fields.length];
                    for (int i = 0; i < fields.length; i++)
                        arguments[i] = fields[i].getAccessor().invoke(value);
                } else arguments = new Object[] {value};
                Object constructed = factory.invoke(null, arguments);
                if (!schema.get("shape").getAsString().startsWith("REJECTED:")) {
                    @SuppressWarnings("unchecked")
                    PacketCodec<Object> codec =
                            (PacketCodec<Object>) owner.getField("CODEC").get(null);
                    assertArrayEquals(body(codec, canonical), body(codec, constructed), factory.toString());
                }
                cases++;
            }
        }
        assertEquals(467, cases);
    }

    @Test
    void omittedOptionalsDefaultToEmptyAndKeepDistinctWireLimits() {
        var text = Component.text("raw");
        var component = new FilterableComponent(text);
        assertEquals(new FilterableComponent(text, Optional.empty()), component);
        assertTrue(component.filtered().isEmpty());
        roundTrip(FilterableComponent.CODEC, component);
        var shortText = new FilterableString32("text");
        assertEquals(new FilterableString32("text", Optional.empty()), shortText);
        roundTrip(FilterableString32.CODEC, shortText);
        roundTrip(FilterableString1024.CODEC, new FilterableString1024("a".repeat(33)));
        var output = Unpooled.buffer();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FilterableString32.CODEC.encode(output, new FilterableString32("a".repeat(33))));
        } finally {
            output.release();
        }
    }

    private static byte[] body(PacketCodec<Object> codec, Object value) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, value);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static <T> void roundTrip(PacketCodec<T> codec, T value) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, value);
            assertEquals(value, codec.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
