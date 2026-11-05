package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaReferencesTest {
    private static final String TYPE = "org.stackanvil.packetforge.protocol.type.fixture.Value";
    private final JavaReferences references = new JavaReferences(Set.of(TYPE, TYPE + "Longer"));

    @Test
    void resolvesGenericArrayTypesAndExactStaticOwners() {
        assertEquals(
                Set.of(model(TYPE), model(TYPE + "Longer")),
                references.type("java.util.Map<" + TYPE + "[], java.util.List<" + TYPE + "Longer>>"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + ".CODEC.decode(input)"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + ".FIRST"));
        assertEquals(Set.of(model(TYPE + "Longer")), references.expression(TYPE + "Longer.CODEC"));
    }

    @Test
    void resolvesLambdasConstructorsClassLiteralsAndMethodReferences() {
        assertEquals(Set.of(model(TYPE)), references.expression("() -> new " + TYPE + "()"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + ".class"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + "::new"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + "::values"));
        assertEquals(Set.of(model(TYPE)), references.expression(TYPE + ".CODEC::decode"));
    }

    @Test
    void resolvesDispatchFieldsAndPayloadAccessors() {
        var table = Set.<SchemaReference>of(new SchemaReference.Dispatch("items"));
        assertEquals(table, references.expression("org.stackanvil.packetforge.protocol.GeneratedWireCodecs.items"));
        assertEquals(table, references.expression("GeneratedWireCodecs.items_payloads()"));
        assertEquals(table, references.expression("GeneratedWireCodecs::items_payloads"));
    }

    @Test
    void ignoresStringsCommentsAndUnrelatedOwners() {
        assertEquals(Set.of(), references.expression("\"" + TYPE + ".CODEC GeneratedWireCodecs.items\""));
        assertEquals(
                Set.of(),
                references.statements("// " + TYPE + "\n System.out.println(\"GeneratedWireCodecs.items\");"));
        assertEquals(Set.of(), references.expression("some.Other.items_payloads()"));
    }

    @Test
    void discoversMethodSignaturesAndBodiesAndReportsMalformedFragments() {
        assertEquals(
                Set.of(model(TYPE)),
                references.member("private static " + TYPE + " helper() { return " + TYPE + ".FIRST; }"));
        assertThrows(SourceIndex.Unsupported.class, () -> references.expression("new broken("));
        assertEquals(Set.of(model(TYPE + "Missing")), references.type(TYPE + "Missing"));
    }

    private static SchemaReference.Model model(String name) {
        return new SchemaReference.Model(name);
    }
}
