package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Recovers boolean context gates and context-selected codec fields. */
final class ContextModifierAnalyzer {
    private final Analyzer analyzer;

    ContextModifierAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    ContextRule lower(Expression modifier, SourceIndex.Source source, Model root) {
        if (modifier instanceof NameExpr name) {
            Expression initializer = source.declaration()
                    .getFieldByName(name.getNameAsString())
                    .flatMap(f -> f.getVariable(0).getInitializer())
                    .orElseThrow(() -> new Unsupported("Missing codec modifier field"));
            if (!(initializer instanceof LambdaExpr lambda)
                    || lambda.getParameters().size() != 2
                    || !(lambda.getBody() instanceof ExpressionStmt expression)
                    || !(expression.getExpression() instanceof ObjectCreationExpr created)
                    || created.getAnonymousClassBody().isEmpty())
                throw new Unsupported("Unknown codec modifier initializer");
            String original = lambda.getParameter(0).getNameAsString(),
                    context = lambda.getParameter(1).getNameAsString();
            var members = created.getAnonymousClassBody().get();
            var reader = method(members, "decode");
            var writer = method(members, "encode");
            var read = reader.getBody().orElseThrow();
            var write = writer.getBody().orElseThrow();
            if (read.getStatements().size() != 1
                    || !(read.getStatement(0) instanceof IfStmt readGate)
                    || write.getStatements().size() != 2
                    || !(write.getStatement(0) instanceof IfStmt writeGate)
                    || readGate.getElseStmt().isEmpty()
                    || writeGate.getElseStmt().isPresent())
                throw new Unsupported("Codec modifier does not only gate its delegate");
            String predicate = negativeFlag(readGate.getCondition(), context);
            if (!negativeFlag(writeGate.getCondition(), context).equals(predicate))
                throw new Unsupported("Codec modifier read and write gates differ");
            String rejection = thrownMessage(readGate.getThenStmt(), "SkipPacketDecoderException");
            if (!rejection.equals(thrownMessage(writeGate.getThenStmt(), "SkipPacketEncoderException")))
                throw new Unsupported("Codec modifier rejection messages differ");
            Statement accepted = one(readGate.getElseStmt().get());
            if (!(accepted instanceof ReturnStmt returned)
                    || !unwrapped(returned.getExpression().orElseThrow())
                            .toString()
                            .equals(original
                                    + ".decode("
                                    + reader.getParameter(0).getNameAsString()
                                    + ")")
                    || !write.getStatement(1)
                            .toString()
                            .equals(original
                                    + ".encode("
                                    + writer.getParameter(0).getNameAsString()
                                    + ", "
                                    + writer.getParameter(1).getNameAsString()
                                    + ");")) throw new Unsupported("Codec gate changes the accepted wire layout");
            declaredFlag(source, predicate);
            return new ContextRule(predicate, root.originalName(), "", rejection);
        }
        if (modifier instanceof MethodCallExpr call
                && call.getScope().isEmpty()
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof LambdaExpr lambda
                && lambda.getParameters().size() == 1
                && lambda.getBody() instanceof ExpressionStmt expression
                && expression.getExpression() instanceof ConditionalExpr selection) {
            var helper = source.declaration().getMethodsByName(call.getNameAsString());
            if (helper.size() != 1
                    || !helper.getFirst().isStatic()
                    || helper.getFirst().getParameters().size() != 1
                    || helper.getFirst().getBody().isEmpty()) throw new Unsupported("Unknown codec selection helper");
            var returned = helper.getFirst().getBody().get();
            if (returned.getStatements().size() != 1
                    || !(returned.getStatement(0) instanceof ReturnStmt result)
                    || !(result.getExpression().orElse(null) instanceof LambdaExpr factory)
                    || factory.getParameters().size() != 2
                    || !(factory.getBody() instanceof ExpressionStmt body)
                    || !(body.getExpression() instanceof ObjectCreationExpr created)
                    || created.getAnonymousClassBody().isEmpty())
                throw new Unsupported("Codec selection helper has extra operations");
            var members = created.getAnonymousClassBody().get();
            String getter = helper.getFirst().getParameter(0).getNameAsString(),
                    context = factory.getParameter(1).getNameAsString();
            for (String name : List.of("decode", "encode")) {
                var method = method(members, name);
                var block = method.getBody().orElseThrow();
                if (block.getStatements().size() != 1)
                    throw new Unsupported("Codec selection helper has extra control flow");
                Expression operation;
                if (name.equals("decode") && block.getStatement(0) instanceof ReturnStmt decoded)
                    operation = unwrapped(decoded.getExpression().orElseThrow());
                else if (name.equals("encode") && block.getStatement(0) instanceof ExpressionStmt encoded)
                    operation = encoded.getExpression();
                else throw new Unsupported("Codec selector does not forward its operation");
                if (!(operation instanceof MethodCallExpr delegated)
                        || !delegated.getNameAsString().equals(name)
                        || delegated.getArguments().size()
                                != method.getParameters().size()
                        || delegated.getScope().isEmpty())
                    throw new Unsupported("Codec selection helper transforms its payload");
                for (int i = 0; i < delegated.getArguments().size(); i++)
                    if (!delegated
                            .getArgument(i)
                            .toString()
                            .equals(method.getParameter(i).getNameAsString()))
                        throw new Unsupported("Codec selector changes its arguments");
                Expression receiver = unwrapped(delegated.getScope().get());
                if (!(receiver instanceof MethodCallExpr apply)
                        || !apply.getNameAsString().equals("apply")
                        || !apply.getScope().map(Object::toString).orElse("").equals(getter)
                        || apply.getArguments().size() != 1
                        || !apply.getArgument(0).toString().equals(context))
                    throw new Unsupported("Codec selector does not use the captured context");
            }
            String predicate =
                    flag(selection.getCondition(), lambda.getParameter(0).getNameAsString());
            Model allowed = selected(selection.getThenExpr(), source),
                    fallback = selected(selection.getElseExpr(), source);
            if (!allowed.originalName().equals(root.originalName())
                    || allowed.fields().size() != fallback.fields().size())
                throw new Unsupported("Context selector does not preserve its packet layout");
            for (int i = 0; i < allowed.fields().size(); i++)
                if (!allowed.fields()
                                .get(i)
                                .name()
                                .equals(fallback.fields().get(i).name())
                        || !allowed.fields()
                                .get(i)
                                .read()
                                .type()
                                .equals(fallback.fields().get(i).read().type()))
                    throw new Unsupported("Context-selected packet fields have different types");
            declaredFlag(source, predicate);
            return new ContextRule(predicate, allowed.originalName(), fallback.originalName(), "");
        }
        throw new Unsupported("Unknown context-dependent codec modifier");
    }

    private void declaredFlag(SourceIndex.Source source, String predicate) {
        var contexts = source.declaration().getMembers().stream()
                .filter(m -> m instanceof ClassOrInterfaceDeclaration c
                        && c.getNameAsString().equals("Context")
                        && c.isInterface())
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .toList();
        if (contexts.size() != 1) throw new Unsupported("Codec context has no declared flag interface");
        var methods = contexts.getFirst().getMethodsByName(predicate);
        if (methods.size() != 1
                || !methods.getFirst().getTypeAsString().equals("boolean")
                || !methods.getFirst().getParameters().isEmpty()
                || methods.getFirst().getBody().isPresent())
            throw new Unsupported("Codec predicate is not a declared boolean context flag");
    }

    private Model selected(Expression expression, SourceIndex.Source source) {
        if (!(expression instanceof FieldAccessExpr field))
            throw new Unsupported("Selected packet codec is not a field");
        return analyzer.model(analyzer.index.resolve(field.getScope().toString(), source), field.getNameAsString());
    }

    private MethodDeclaration method(List<BodyDeclaration<?>> members, String name) {
        var methods = members.stream()
                .filter(m ->
                        m instanceof MethodDeclaration d && d.getNameAsString().equals(name))
                .map(m -> (MethodDeclaration) m)
                .toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous modifier method: " + name);
        return methods.getFirst();
    }

    private String negativeFlag(Expression expression, String context) {
        if (!(expression instanceof UnaryExpr unary) || unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT)
            throw new Unsupported("Codec gate is not a negative boolean flag");
        return flag(unary.getExpression(), context);
    }

    private String flag(Expression expression, String context) {
        if (!(expression instanceof MethodCallExpr call)
                || !call.getScope().map(Object::toString).orElse("").equals(context)
                || !call.getArguments().isEmpty())
            throw new Unsupported("Codec context predicate is not a direct flag accessor");
        return call.getNameAsString();
    }

    private Statement one(Statement statement) {
        return statement instanceof BlockStmt block && block.getStatements().size() == 1
                ? block.getStatement(0)
                : statement;
    }

    private Expression unwrapped(Expression expression) {
        while (expression instanceof CastExpr || expression instanceof EnclosedExpr)
            expression = expression instanceof CastExpr cast
                    ? cast.getExpression()
                    : expression.asEnclosedExpr().getInner();
        return expression;
    }

    private String thrownMessage(Statement statement, String expected) {
        if (!(one(statement) instanceof ThrowStmt thrown)
                || !(thrown.getExpression() instanceof ObjectCreationExpr exception)
                || !exception.getTypeAsString().equals(expected)
                || exception.getArguments().size() != 1
                || !(exception.getArgument(0) instanceof StringLiteralExpr message))
            throw new Unsupported("Codec gate does not throw a literal rejection");
        return message.asString();
    }
}
