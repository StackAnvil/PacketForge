package org.stackanvil.packetforge.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.Map;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Renames Java symbols in parsed fragments, leaving string literals and source metadata intact. */
final class JavaTypeNames {
    private final JavaParser parser =
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Map<String, String> names;

    JavaTypeNames(Map<String, String> names) {
        this.names = Map.copyOf(names);
    }

    String type(String source) {
        return rename(parsed(parser.parseType(source))).toString();
    }

    String expression(String source) {
        return source.isBlank()
                ? source
                : rename(parsed(parser.parseExpression(source))).toString();
    }

    String statements(String source) {
        var block = rename(parsed(parser.parseBlock("{" + source + "}")));
        return String.join(
                "\n", block.getStatements().stream().map(Object::toString).toList());
    }

    String member(String source) {
        return rename(parsed(parser.parseBodyDeclaration(source))).toString();
    }

    private <T extends Node> T rename(T root) {
        for (var type : root.findAll(ClassOrInterfaceType.class)) {
            if (type.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                    && parent.getScope().orElse(null) == type) continue;
            String replacement = replacement(type.getNameWithScope());
            if (replacement == null) continue;
            int split = replacement.lastIndexOf('.');
            type.setName(replacement.substring(split + 1));
            if (split < 0) type.removeScope();
            else type.setScope(parsed(parser.parseClassOrInterfaceType(replacement.substring(0, split))));
        }
        for (var field : root.findAll(FieldAccessExpr.class)) {
            if (field.getParentNode().orElse(null) instanceof FieldAccessExpr parent && parent.getScope() == field)
                continue;
            String qualified = qualified(field);
            String replacement = qualified == null ? null : replacement(qualified);
            if (replacement == null) continue;
            int split = replacement.lastIndexOf('.');
            if (split < 0) throw new Unsupported("Qualified Java symbol lost its owner: " + qualified);
            field.setScope(parsed(parser.parseExpression(replacement.substring(0, split))));
            field.setName(replacement.substring(split + 1));
        }
        for (var name : root.findAll(NameExpr.class)) {
            if (name.getParentNode().orElse(null) instanceof FieldAccessExpr) continue;
            String replacement = names.get(name.getNameAsString());
            if (replacement != null) name.setName(replacement);
        }
        return root;
    }

    private String replacement(String source) {
        String candidate = source;
        while (true) {
            String target = names.get(candidate);
            if (target != null) return target + source.substring(candidate.length());
            int split = candidate.lastIndexOf('.');
            if (split < 0) return null;
            candidate = candidate.substring(0, split);
        }
    }

    private static String qualified(Expression expression) {
        if (expression instanceof NameExpr name) return name.getNameAsString();
        if (expression instanceof FieldAccessExpr field) {
            String owner = qualified(field.getScope());
            return owner == null ? null : owner + "." + field.getNameAsString();
        }
        return null;
    }

    private static <T extends Node> T parsed(ParseResult<T> result) {
        if (!result.isSuccessful()) throw new Unsupported("Cannot rename generated Java: " + result.getProblems());
        return result.getResult().orElseThrow();
    }
}
