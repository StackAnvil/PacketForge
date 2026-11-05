package org.stackanvil.packetforge.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/**
 * Matches syntax with dollar-prefixed identifier and type bindings. Operators, calls, argument order,
 * and statement counts remain exact; patterns have no expression or statement wildcards.
 *
 * <p>Comments, parentheses, final local modifiers, and single-statement braces do not affect matches.
 */
final class AstPattern {
    private final Node pattern;

    private AstPattern(Node pattern) {
        this.pattern = pattern;
    }

    static AstPattern expression(String source) {
        return parsed(parser().parseExpression(source), "expression", source);
    }

    static AstPattern statement(String source) {
        return parsed(parser().parseStatement(source), "statement", source);
    }

    private static AstPattern parsed(ParseResult<? extends Node> result, String kind, String source) {
        if (!result.isSuccessful()) throw new IllegalArgumentException("Invalid " + kind + " pattern: " + source);
        return new AstPattern(result.getResult().orElseThrow());
    }

    static Bindings bindings() {
        return new Bindings();
    }

    Optional<Bindings> match(Node actual) {
        return match(actual, bindings());
    }

    Optional<Bindings> match(Node actual, Bindings known) {
        var bindings = new Bindings(known);
        return matches(pattern, actual, bindings) ? Optional.of(bindings) : Optional.empty();
    }

    Bindings require(Node actual, Bindings known, String reason) {
        return match(actual, known).orElseThrow(() -> new Unsupported(reason));
    }

    private static JavaParser parser() {
        return new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    }

    private static boolean matches(Object expected, Object actual, Bindings bindings) {
        if (expected instanceof Node expectedNode && actual instanceof Node actualNode) {
            return matchesNode(unwrap(expectedNode), unwrap(actualNode), bindings);
        }
        if (expected instanceof Optional<?> left && actual instanceof Optional<?> right) {
            return matches(left.orElse(null), right.orElse(null), bindings);
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!matches(left.get(i), right.get(i), bindings)) return false;
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static boolean matchesNode(Node expected, Node actual, Bindings bindings) {
        if (expected instanceof ClassOrInterfaceType type
                && type.getNameAsString().startsWith("$")
                && type.getScope().isEmpty()
                && type.getTypeArguments().isEmpty()) {
            return actual instanceof Type actualType
                    && bindings.bindType(type.getNameAsString().substring(1), actualType);
        }
        if (expected instanceof NameExpr name && name.getNameAsString().startsWith("$")) {
            return actual instanceof NameExpr value
                    && bindings.bindName(name.getNameAsString().substring(1), value.getNameAsString());
        }
        if (expected instanceof SimpleName name && name.getIdentifier().startsWith("$")) {
            return actual instanceof SimpleName value
                    && bindings.bindName(name.getIdentifier().substring(1), value.getIdentifier());
        }
        if (expected.getClass() != actual.getClass()) return false;
        if (expected instanceof IntegerLiteralExpr left && actual instanceof IntegerLiteralExpr right) {
            return left.asNumber().longValue() == right.asNumber().longValue();
        }
        if (expected instanceof LongLiteralExpr left && actual instanceof LongLiteralExpr right) {
            return left.asNumber().longValue() == right.asNumber().longValue();
        }
        if (expected instanceof StringLiteralExpr left && actual instanceof StringLiteralExpr right) {
            return left.asString().equals(right.asString());
        }
        for (var property : expected.getMetaModel().getAllPropertyMetaModels()) {
            if (property.getName().equals("comment")) continue;
            Object left = property.getValue(expected), right = property.getValue(actual);
            if (expected instanceof MethodReferenceExpr
                    && property.getName().equals("identifier")
                    && left instanceof String identifier
                    && identifier.startsWith("$")) {
                if (!(right instanceof String value) || !bindings.bindName(identifier.substring(1), value))
                    return false;
                continue;
            }
            if (property.getName().equals("modifiers")
                    && (expected instanceof VariableDeclarationExpr || expected instanceof Parameter)) {
                left = withoutFinal(left);
                right = withoutFinal(right);
            }
            if (!matches(left, right, bindings)) return false;
        }
        return true;
    }

    private static Object withoutFinal(Object value) {
        if (!(value instanceof List<?> modifiers)) return value;
        return modifiers.stream()
                .filter(item -> !(item instanceof Modifier modifier) || modifier.getKeyword() != Modifier.Keyword.FINAL)
                .toList();
    }

    private static Node unwrap(Node node) {
        while (true) {
            if (node instanceof EnclosedExpr enclosed) node = enclosed.getInner();
            else if (node instanceof BlockStmt block && block.getStatements().size() == 1) node = block.getStatement(0);
            else return node;
        }
    }

    static final class Bindings {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, Type> types = new HashMap<>();

        private Bindings() {}

        private Bindings(Bindings source) {
            names.putAll(source.names);
            types.putAll(source.types);
        }

        Bindings withName(String key, String value) {
            var result = new Bindings(this);
            if (!result.bindName(key, value)) throw new IllegalArgumentException("Conflicting name binding: " + key);
            return result;
        }

        String name(String key) {
            return Optional.ofNullable(names.get(key))
                    .orElseThrow(() -> new IllegalArgumentException("Unbound name: " + key));
        }

        Type type(String key) {
            return Optional.ofNullable(types.get(key))
                    .orElseThrow(() -> new IllegalArgumentException("Unbound type: " + key))
                    .clone();
        }

        private boolean bindName(String key, String value) {
            String previous = names.putIfAbsent(key, value);
            return previous == null || previous.equals(value);
        }

        private boolean bindType(String key, Type value) {
            Type normalized = value.clone();
            normalized.walk(Node::removeComment);
            Type previous = types.putIfAbsent(key, normalized);
            return previous == null || previous.equals(normalized);
        }
    }
}
