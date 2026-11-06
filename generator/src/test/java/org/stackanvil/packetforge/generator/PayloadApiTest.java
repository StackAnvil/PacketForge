package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

class PayloadApiTest {
    private static final Wire INT = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT");

    private static Dispatch table(String name, String source, String field, DispatchCase... cases) {
        return new Dispatch(
                name,
                INT,
                List.of(cases),
                source,
                "fixture",
                new Wire("org.stackanvil.packetforge.commons.Variant<Integer>", "", "dispatch:" + name),
                "",
                field);
    }

    @Test
    void bindsEqualDiscriminatorTypesToTheirOwnPayloadsInsideCollections() {
        var first = table("dispatch_1", "net.minecraft.fixture.First", "STREAM_CODEC", new DispatchCase("0", INT));
        var second = table("dispatch_2", "net.minecraft.fixture.Second", "STREAM_CODEC", new DispatchCase("0", INT));
        var api = new PayloadApi(List.of(first, second));
        var wire = new Wire(
                "java.util.Map<org.stackanvil.packetforge.commons.Variant<Integer>, java.util.List<org.stackanvil.packetforge.commons.Variant<Integer>>>",
                "fixture",
                "map<dispatch:dispatch_1,list<dispatch:dispatch_2>>",
                "projection");
        var model =
                new Model("Fixture", "Fixture", List.of(new Field("values", "source", wire, wire)), List.of(), false);
        var field = api.models(List.of(model)).getFirst().fields().getFirst();
        assertEquals(
                "java.util.Map<org.stackanvil.packetforge.protocol.payload.FirstPayload, java.util.List<org.stackanvil.packetforge.protocol.payload.SecondPayload>>",
                field.read().type());
        assertEquals("projection", field.read().projection());
        assertEquals(wire.codec(), field.read().codec());
        assertEquals(wire.shape(), field.read().shape());
    }

    @Test
    void distinguishesCodecVariantsAndRejectsAmbiguousNamesAndUnboundShapes() {
        var config =
                table("dispatch_1", "net.minecraft.fixture.Payload", "CONFIG_STREAM_CODEC", new DispatchCase("0", INT));
        var play = table(
                "dispatch_2", "net.minecraft.fixture.Payload", "GAMEPLAY_STREAM_CODEC", new DispatchCase("0", INT));
        var api = new PayloadApi(List.of(config, play));
        assertNotEquals(api.type(config.name()), api.type(play.name()));
        assertThrows(UnsupportedOperationException.class, () -> api.types().clear());
        var collision = table(
                "dispatch_3",
                "net.minecraft.fixture.Collide",
                "STREAM_CODEC",
                new DispatchCase("0", INT, "minecraft:a-b"),
                new DispatchCase("1", INT, "minecraft:a_b"));
        assertThrows(Unsupported.class, () -> new PayloadApi(List.of(collision)));
        assertThrows(Unsupported.class, () -> api.type("dispatch_404"));
        var broken = new Wire("int", "fixture", "dispatch:dispatch_1");
        var model = new Model(
                "Fixture", "Fixture", List.of(new Field("value", "source", broken, broken)), List.of(), false);
        assertThrows(Unsupported.class, () -> api.models(List.of(model)));
    }
}
