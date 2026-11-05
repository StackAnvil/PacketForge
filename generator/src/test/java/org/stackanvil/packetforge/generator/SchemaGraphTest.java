package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.generator.Analyzer.ContextRule;
import org.stackanvil.packetforge.generator.Analyzer.Dispatch;
import org.stackanvil.packetforge.generator.Analyzer.DispatchCase;
import org.stackanvil.packetforge.generator.Analyzer.Field;
import org.stackanvil.packetforge.generator.Analyzer.Model;
import org.stackanvil.packetforge.generator.Analyzer.Program;
import org.stackanvil.packetforge.generator.Analyzer.Registration;
import org.stackanvil.packetforge.generator.Analyzer.Wire;

class SchemaGraphTest {
    private static final String TYPES = "org.stackanvil.packetforge.protocol.type.fixture.";
    private static final Wire INT = new Wire("int", "WireCodecs.VAR_INT", "VAR_INT");

    @Test
    void followsRecursiveModelsTablesAndFallbacksInStableOrder() {
        Model root = model("Root", field("nested", wire("Nested")), field("union", tableWire("outer")));
        Model nested = model("Nested", field("parent", wire("Root")));
        Model fallback = model("Fallback");
        Model leaf = model("Leaf");
        Dispatch inner = table("inner", wire("Leaf"), null);
        Dispatch outer = table("outer", tableWire("inner"), wire("Fallback"));
        var graph = new SchemaGraph(
                Map.of("Root", root, "Nested", nested, "Leaf", leaf, "Fallback", fallback), List.of(outer, inner));

        assertEquals("", graph.missing(registration("Root", null)));
        var selection = graph.select(List.of(registration("Root", null)));
        assertEquals(List.of(fallback, leaf, nested, root), selection.models());
        assertEquals(List.of(outer, inner), selection.dispatches());
    }

    @Test
    void retainsWriteTypesProgramHelpersAndConstructorGuards() {
        var program = new Program(
                "return new " + TYPES + "Decode();",
                TYPES + "Encode.CODEC.encode(output, value);",
                List.of("diagnostic-only: " + TYPES + "Unused"),
                List.of("private static " + TYPES + "Helper helper() { return null; }"));
        Model root = new Model(
                "Root",
                TYPES + "Root",
                List.of(new Field("value", "ignored", INT, wire("Write"))),
                List.of(),
                true,
                List.of("if (value == null) throw new " + TYPES + "Guard();"),
                program);
        var graph = new SchemaGraph(
                Map.of(
                        "Root",
                        root,
                        "Decode",
                        model("Decode"),
                        "Encode",
                        model("Encode"),
                        "Helper",
                        model("Helper"),
                        "Write",
                        model("Write"),
                        "Guard",
                        model("Guard")),
                List.of());

        assertEquals(
                List.of("Decode", "Encode", "Guard", "Helper", "Root", "Write"),
                graph.select(List.of(registration("Root", null))).models().stream()
                        .map(Model::originalName)
                        .toList());
    }

    @Test
    void retainsBothContextBranchesAndSkipsUnsupportedRegistrations() {
        Model root = model("Root");
        Model allowed = model("Allowed");
        Model fallback = model("Fallback");
        var graph = new SchemaGraph(Map.of("Root", root, "Allowed", allowed, "Fallback", fallback), List.of());
        var rule = new ContextRule("enabled", "Allowed", "Fallback", "rejected");
        assertEquals(
                List.of(allowed, fallback, root),
                graph.select(List.of(registration("Root", rule))).models());
        var unsupported = new Registration(
                "GAME", "CLIENTBOUND", 1, "missing", "Missing", "", "", 1, false, "unsupported", TYPES + "Missing");
        assertEquals(List.of(), graph.select(List.of(unsupported)).models());
        assertEquals(
                "Unresolved context codec model: Missing",
                graph.missing(registration("Root", new ContextRule("enabled", "Missing", "", ""))));
    }

    @Test
    void prunesDeadSchemasAndIgnoresDiagnosticText() {
        String diagnostic = TYPES + "Missing GeneratedWireCodecs.absent";
        Wire text = new Wire("String", "WireCodecs.unit(\"" + diagnostic + "\")", diagnostic, diagnostic);
        Model root = model("Root", field("text", text));
        Model dead = model("Dead", field("broken", new Wire("?", "invalid(", "")));
        var graph = new SchemaGraph(Map.of("Root", root, "Dead", dead), List.of(table("dead", wire("Missing"), null)));
        assertEquals("", graph.missing(registration("Root", null)));
        var selection = graph.select(List.of(registration("Root", null)));
        assertEquals(List.of(root), selection.models());
        assertEquals(List.of(), selection.dispatches());
    }

    @Test
    void rejectsReachableMissingTablesModelsAndConflictingDefinitions() {
        Model root = model("Root", field("missing", tableWire("absent")));
        assertEquals(
                "Unresolved recursive codec table: absent",
                new SchemaGraph(Map.of("Root", root), List.of()).missing(registration("Root", null)));
        root = model("Root", field("missing", wire("Missing")));
        var graph = new SchemaGraph(Map.of("Root", root), List.of());
        assertEquals("Unresolved generated model: " + TYPES + "Missing", graph.missing(registration("Root", null)));
        assertThrows(SourceIndex.Unsupported.class, () -> graph.select(List.of(registration("Root", null))));
        Model conflict = new Model("Other", TYPES + "Root", List.of(), List.of(), true);
        assertEquals(
                "Conflicting generated models: " + TYPES + "Root",
                new SchemaGraph(Map.of("Root", root, "Other", conflict), List.of())
                        .missing(registration("Root", null)));
    }

    private static Model model(String name, Field... fields) {
        return new Model(name, TYPES + name, List.of(fields), List.of(), name.equals("Root"));
    }

    private static Field field(String name, Wire wire) {
        return new Field(name, "ignored", wire, wire);
    }

    private static Wire wire(String name) {
        return new Wire(TYPES + name, TYPES + name + ".CODEC", "model:" + name);
    }

    private static Wire tableWire(String name) {
        return new Wire(
                "Object", "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name, "dispatch:" + name);
    }

    private static Dispatch table(String name, Wire value, Wire fallback) {
        return new Dispatch(
                name, INT, List.of(new DispatchCase("0", value)), "", "", INT, "", "STREAM_CODEC", fallback);
    }

    private static Registration registration(String name, ContextRule rule) {
        return new Registration("GAME", "CLIENTBOUND", 0, name, name, "", "", 1, true, "", TYPES + name, rule);
    }
}
