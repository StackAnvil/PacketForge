package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Lowers optional records whose first unsigned byte doubles as an absence marker. */
final class SentinelOptionalAnalyzer {
    private final Analyzer analyzer;
    SentinelOptionalAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    Wire lower(MethodCallExpr codec, SourceIndex.Source source) {
        if (!(source.declaration() instanceof RecordDeclaration record) || codec.getArguments().size() != 2 || !(codec.getArgument(0) instanceof MethodReferenceExpr writerRef) || !(codec.getArgument(1) instanceof MethodReferenceExpr readerRef)) throw new Unsupported("Not an optional-record codec");
        var readers = source.declaration().getMethodsByName(readerRef.getIdentifier()); var writers = source.declaration().getMethodsByName(writerRef.getIdentifier());
        if (readers.size() != 1 || writers.size() != 1) throw new Unsupported("Ambiguous optional-record methods");
        var reader = readers.getFirst(); var writer = writers.getFirst();
        if (!reader.getTypeAsString().startsWith("Optional<") || reader.getParameters().size() != 1 || writer.getParameters().size() != 2 || reader.getBody().isEmpty() || writer.getBody().isEmpty()) throw new Unsupported("Not an optional-record reader and writer");
        String input = reader.getParameter(0).getNameAsString(), output = writer.getParameter(0).getNameAsString(), optional = writer.getParameter(1).getNameAsString();
        var body = reader.getBody().get();
        if (body.getStatements().size() != 2 || !(body.getStatement(0) instanceof ExpressionStmt first) || !(first.getExpression() instanceof VariableDeclarationExpr variables) || variables.getVariables().size() != 1 || !(body.getStatement(1) instanceof IfStmt branch)) throw new Unsupported("Optional-record reader has extra operations");
        var discriminator = variables.getVariable(0);
        if (!discriminator.getInitializer().map(Object::toString).orElse("").equals(input + ".readUnsignedByte()") || !branch.getCondition().toString().equals(discriminator.getNameAsString() + " > 0") || !branch.getThenStmt().isBlockStmt() || branch.getElseStmt().isEmpty() || !returnsEmpty(branch.getElseStmt().get())) throw new Unsupported("Optional-record reader has an unsupported sentinel");
        var present = branch.getThenStmt().asBlockStmt();
        if (present.getStatements().isEmpty() || !(present.getStatement(present.getStatements().size() - 1) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof MethodCallExpr of) || !of.getScope().map(Object::toString).orElse("").equals("Optional") || !of.getNameAsString().equals("of") || of.getArguments().size() != 1 || !(of.getArgument(0) instanceof ObjectCreationExpr created) || !analyzer.index.resolve(created.getType().getNameWithScope(), source).equals(source.name()) || created.getArguments().size() != record.getParameters().size()) throw new Unsupported("Optional-record reader does not construct its declared payload");
        List<VariableDeclarator> decoded = new ArrayList<>(); decoded.add(discriminator);
        for (int i = 0; i < present.getStatements().size() - 1; i++) {
            if (!(present.getStatement(i) instanceof ExpressionStmt expression) || !(expression.getExpression() instanceof VariableDeclarationExpr declaration) || declaration.getVariables().size() != 1 || declaration.getVariable(0).getInitializer().isEmpty()) throw new Unsupported("Optional-record reader transforms a wire field");
            decoded.add(declaration.getVariable(0));
        }
        Map<String, String> fieldByVariable = new HashMap<>();
        for (int i = 0; i < created.getArguments().size(); i++) {
            if (!(created.getArgument(i) instanceof NameExpr name) || fieldByVariable.put(name.getNameAsString(), record.getParameter(i).getNameAsString()) != null) throw new Unsupported("Optional-record constructor transforms or repeats a wire field");
        }
        var writeBody = writer.getBody().get();
        if (writeBody.getStatements().size() != 1 || !(writeBody.getStatement(0) instanceof IfStmt writeBranch) || !writeBranch.getCondition().toString().equals(optional + ".isPresent()") || !writeBranch.getThenStmt().isBlockStmt() || writeBranch.getElseStmt().isEmpty()) throw new Unsupported("Optional-record writer has extra operations");
        Statement absent = writeBranch.getElseStmt().get(); if (absent instanceof BlockStmt block && block.getStatements().size() == 1) absent = block.getStatement(0);
        if (!absent.toString().equals(output + ".writeByte(0);")) throw new Unsupported("Optional-record writer uses a different sentinel");
        var encoded = writeBranch.getThenStmt().asBlockStmt();
        if (encoded.getStatements().size() != decoded.size() + 1 || !(encoded.getStatement(0) instanceof ExpressionStmt aliasStatement) || !(aliasStatement.getExpression() instanceof VariableDeclarationExpr aliases) || aliases.getVariables().size() != 1 || !aliases.getVariable(0).getInitializer().map(Object::toString).orElse("").equals(optional + ".get()")) throw new Unsupported("Optional-record writer changes its payload");
        String alias = aliases.getVariable(0).getNameAsString(); List<Field> fields = new ArrayList<>();
        for (int i = 0; i < decoded.size(); i++) {
            var variable = decoded.get(i); String fieldName = fieldByVariable.get(variable.getNameAsString());
            if (fieldName == null) throw new Unsupported("Optional-record reader discards a wire field");
            Expression read = staticBuffer(variable.getInitializer().orElseThrow(), input, false);
            Wire readWire = analyzer.read(read, input, source);
            if (!(encoded.getStatement(i + 1) instanceof ExpressionStmt expression) || !(expression.getExpression() instanceof MethodCallExpr write)) throw new Unsupported("Optional-record writer transforms a wire field");
            write = (MethodCallExpr) staticBuffer(write, output, true);
            for (var field : write.findAll(FieldAccessExpr.class)) if (field.getScope().toString().equals(alias)) field.setScope(new ThisExpr());
            Wire writeWire = analyzer.write(write, output, fieldName, readWire, source);
            if (!readWire.type().equals(writeWire.type()) || !readWire.shape().equals(writeWire.shape())) throw new Unsupported("Optional-record reader and writer wire types differ");
            fields.add(new Field(fieldName, Analyzer.declaredType(fieldName, source), readWire, writeWire));
        }
        if (fields.size() != record.getParameters().size()) throw new Unsupported("Optional-record layout omits fields");
        String key = source.name() + "#sentinel"; String generated = analyzer.index.generated(source.name()) + "_Payload";
        Model model = new Model(key, generated, List.copyOf(fields), List.of(), false, List.of(), null, "optional unsigned-byte sentinel");
        analyzer.models.put(key, model);
        return new Wire("java.util.Optional<" + generated + ">", "WireCodecs.sentinelOptional(" + generated + ".CODEC, WireCodecs.UNSIGNED_BYTE, 0)", "optional<model:" + key + ">");
    }
    private boolean returnsEmpty(Statement statement) {
        if (statement instanceof BlockStmt block && block.getStatements().size() == 1) statement = block.getStatement(0);
        return statement.toString().equals("return Optional.empty();");
    }
    private Expression staticBuffer(Expression expression, String buffer, boolean write) {
        Expression copy = expression.clone();
        if (copy instanceof MethodCallExpr call && call.getScope().map(Object::toString).orElse("").equals("FriendlyByteBuf") && call.getNameAsString().startsWith(write ? "write" : "read") && !call.getArguments().isEmpty() && call.getArgument(0).toString().equals(buffer)) { call.getArguments().remove(0); call.setScope(new NameExpr(buffer)); }
        return copy;
    }
}
