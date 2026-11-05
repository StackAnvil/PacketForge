package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

class AstPatternTest {
    @Test
    void bindsRenamedLocalsAndRequiresTheirUsesToStayConsistent() {
        var pattern = AstPattern.statement("{ byte $flags = $input.readByte(); return $flags; }");
        var bindings = pattern.match(StaticJavaParser.parseStatement("{ byte mask = bytes.readByte(); return mask; }"))
                .orElseThrow();
        assertEquals("mask", bindings.name("flags"));
        assertEquals("bytes", bindings.name("input"));
        assertTrue(pattern.match(StaticJavaParser.parseStatement("{ byte mask = bytes.readByte(); return other; }"))
                .isEmpty());
    }

    @Test
    void ignoresCommentsParenthesesFinalLocalsAndSingleStatementBraces() {
        var pattern =
                AstPattern.statement("{ int $value = $input.readVarInt(); if (($value & 8) != 0) return $value; }");
        assertTrue(pattern.match(StaticJavaParser.parseStatement("""
                {
                    final int number = (buffer.readVarInt());
                    // Same operations and binding, with a hexadecimal mask.
                    if (((number & 0x08)) != (0)) { return (number); }
                }
                """)).isPresent());
    }

    @Test
    void rejectsChangedOperationsArgumentOrderAndExtraIo() {
        var call = AstPattern.expression("$output.write($first, $second)");
        var known = AstPattern.bindings().withName("first", "left").withName("second", "right");
        assertTrue(call.match(StaticJavaParser.parseExpression("out.write(right, left)"), known)
                .isEmpty());
        assertTrue(call.match(StaticJavaParser.parseExpression("out.other(left, right)"), known)
                .isEmpty());
        var body = AstPattern.statement("{ byte $value = $input.readByte(); return $value; }");
        assertTrue(body.match(StaticJavaParser.parseStatement("{ byte v = in.readByte(); in.readByte(); return v; }"))
                .isEmpty());
        assertTrue(body.match(StaticJavaParser.parseStatement("{ byte v = in.readByte(); return transform(v); }"))
                .isEmpty());
    }

    @Test
    void capturesTypesAndDoesNotMutateExistingBindingsWhenAMatchFails() {
        var pattern = AstPattern.statement("{ $Value $value = new $Value(); return $input.read($value); }");
        var known = AstPattern.bindings().withName("input", "buffer");
        assertTrue(pattern.match(
                        StaticJavaParser.parseStatement("{ Thing v = new Other(); return buffer.read(v); }"), known)
                .isEmpty());
        var matched = pattern.match(
                        StaticJavaParser.parseStatement("{ Thing v = new Thing(); return buffer.read(v); }"), known)
                .orElseThrow();
        assertEquals("Thing", matched.type("Value").asString());
        assertThrows(IllegalArgumentException.class, () -> known.name("value"));
    }

    @Test
    void capturedDollarPrefixedTypeNamesAreDataRatherThanNewPatternVariables() {
        var pattern = AstPattern.statement("{ $Value $value = new $Value(); }");
        assertTrue(pattern.match(StaticJavaParser.parseStatement("{ $Thing value = new Other(); }"))
                .isEmpty());
        assertTrue(pattern.match(StaticJavaParser.parseStatement("{ $Thing value = new $Thing(); }"))
                .isPresent());
    }

    @Test
    void methodReferenceIdentifiersBindToTheirSourceNames() {
        var pattern = AstPattern.expression("$Owner::$method");
        var binding = pattern.match(StaticJavaParser.parseExpression("Handler::apply"))
                .orElseThrow();
        assertEquals("Handler", binding.type("Owner").asString());
        assertEquals("apply", binding.name("method"));
    }

    @Test
    void commentsOnRepeatedTypesDoNotChangeTheirBinding() {
        var pattern = AstPattern.statement("{ $Value $value = new $Value(); }");
        assertTrue(pattern.match(StaticJavaParser.parseStatement("{ Thing /* declared type */ value = new Thing(); }"))
                .isPresent());
    }

    @Test
    void invalidPatternsFailAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> AstPattern.expression("input.read(;"));
        assertThrows(IllegalArgumentException.class, () -> AstPattern.statement("{ return ; else }"));
    }
}
