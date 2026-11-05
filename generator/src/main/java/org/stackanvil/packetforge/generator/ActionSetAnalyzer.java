package org.stackanvil.packetforge.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Extracts enum-held action lambdas selected by a packet-wide fixed mask. */
final class ActionSetAnalyzer {
    private final Analyzer analyzer;
    ActionSetAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    ProgramLowerer.Lowered lower(Expression codec, SourceIndex.Source source, String generated) {
        if (!(codec instanceof MethodCallExpr call) || !call.getNameAsString().equals("codec") || call.getArguments().size() != 2 || !(call.getArgument(0) instanceof MethodReferenceExpr writer) || !(call.getArgument(1) instanceof MethodReferenceExpr reader) || !reader.getIdentifier().equals("new") || !analyzer.index.resolve(reader.getScope().toString(), source).equals(source.name()) || !analyzer.index.resolve(writer.getScope().toString(), source).equals(source.name())) throw new Unsupported("Unknown action-set codec entry points");
        var constructors = source.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration c && c.getParameters().size() == 1 && c.getParameter(0).getTypeAsString().equals("RegistryFriendlyByteBuf")).map(m -> (ConstructorDeclaration) m).toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous action-set buffer constructor");
        var constructor = constructors.getFirst(); String input = constructor.getParameter(0).getNameAsString(); var decode = constructor.getBody().getStatements();
        var write = method(source, writer.getIdentifier(), 1); String output = write.getParameter(0).getNameAsString(); var encode = write.getBody().get().getStatements();
        if (decode.size() != 2 || encode.size() != 2 || !(assignment(decode.get(0), "actions") instanceof MethodCallExpr maskRead) || !maskRead.getScope().map(Object::toString).orElse("").equals(input) || !maskRead.getNameAsString().equals("readEnumSet") || maskRead.getArguments().size() != 1 || !(maskRead.getArgument(0) instanceof ClassExpr enumerationType)) throw new Unsupported("Unknown action mask reader");
        String enumName = analyzer.index.resolve(enumerationType.getTypeAsString(), source); var enumSource = analyzer.index.source(enumName);
        if (!(enumSource.declaration() instanceof EnumDeclaration enumeration)) throw new Unsupported("Action mask target is not an enum");
        if (!encode.get(0).toString().equals(output + ".writeEnumSet(this.actions, " + enumerationType + ");")) throw new Unsupported("Action mask reader/writer types differ");
        var entriesRead = assignment(decode.get(1), "entries");
        if (!(entriesRead instanceof MethodCallExpr readCall) || !readCall.getNameAsString().equals("decode") || !readCall.getArguments().toString().equals("[" + input + "]")) throw new Unsupported("Unknown action entry list reader");
        var factory = listFactory(readCall.getScope().orElseThrow());
        var entriesWrite = expression(encode.get(1));
        if (!(entriesWrite instanceof MethodCallExpr writeCall) || !writeCall.getNameAsString().equals("encode") || !writeCall.getArguments().stream().map(Object::toString).toList().equals(List.of(output, "this.entries")) || !listFactory(writeCall.getScope().orElseThrow()).equals(factory)) throw new Unsupported("Action entry reader/writer factories differ");
        var entryFactory = method(source, factory.getNameAsString(), 1); String actions = entryFactory.getParameter(0).getNameAsString(); var body = entryFactory.getBody().get().getStatements();
        if (!entryFactory.isStatic() || body.size() != 1 || !(body.get(0) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof MethodCallExpr composite) || !composite.getScope().map(Object::toString).orElse("").equals("StreamCodec") || !composite.getNameAsString().equals("of") || composite.getArguments().size() != 2 || !(composite.getArgument(0) instanceof LambdaExpr entryWriter) || !(composite.getArgument(1) instanceof LambdaExpr entryReader) || entryWriter.getParameters().size() != 2 || entryReader.getParameters().size() != 1) throw new Unsupported("Unknown action entry factory");
        var readBody = block(entryReader); var writeBody = block(entryWriter); String entryInput = entryReader.getParameter(0).getNameAsString(), entryOutput = entryWriter.getParameter(0).getNameAsString(), entryValue = entryWriter.getParameter(1).getNameAsString();
        if (readBody.getStatements().size() != 3 || writeBody.getStatements().size() != 2 || !(expression(readBody.getStatement(0)) instanceof VariableDeclarationExpr variables) || variables.getVariables().size() != 1 || !(variables.getVariable(0).getInitializer().orElse(null) instanceof ObjectCreationExpr builderCreation) || !builderCreation.getArguments().toString().equals("[" + entryInput + ".readUUID()]")) throw new Unsupported("Unknown action entry UUID/builder reader");
        String builder = variables.getVariable(0).getNameAsString(); String builderName = analyzer.index.resolve(builderCreation.getTypeAsString(), source); var builderSource = analyzer.index.source(builderName);
        if (!(readBody.getStatement(2) instanceof ReturnStmt built) || !built.getExpression().map(Object::toString).orElse("").equals(builder + ".build()")) throw new Unsupported("Action entry builder result is transformed");
        if (!writeBody.getStatement(0).toString().equals(entryOutput + ".writeUUID(" + entryValue + ".profileId());")) throw new Unsupported("Action entry writer has a different identifier");
        String readerField = actionLoop(readBody.getStatement(1), actions, enumName, enumSource, "read", builder, entryInput);
        String writerField = actionLoop(writeBody.getStatement(1), actions, enumName, enumSource, "write", entryOutput, entryValue);
        verifyBuilder(builderSource);
        var enumConstructors = enumeration.getConstructors();
        if (enumConstructors.size() != 1 || enumConstructors.getFirst().getParameters().size() != 3 || enumConstructors.getFirst().getBody().getStatements().size() != 3) throw new Unsupported("Unknown action enum captures");
        var captured = enumConstructors.getFirst();
        for (int i = 0; i < 3; i++) if (!captured.getBody().getStatement(i).toString().equals("this." + List.of("id", readerField, writerField).get(i) + " = " + captured.getParameter(i).getNameAsString() + ";")) throw new Unsupported("Action enum transforms its captures");
        List<DispatchCase> cases = new ArrayList<>();
        for (var constant : enumeration.getEntries()) {
            if (constant.getArguments().size() != 3 || !(constant.getArgument(0) instanceof IntegerLiteralExpr id) || id.asNumber().intValue() != cases.size() || !(constant.getArgument(1) instanceof LambdaExpr actionReader) || actionReader.getParameters().size() != 2 || !(constant.getArgument(2) instanceof LambdaExpr actionWriter) || actionWriter.getParameters().size() != 2) throw new Unsupported("Unknown action ID or payload lambda");
            String readValue = actionReader.getParameter(0).getNameAsString(), readBuffer = actionReader.getParameter(1).getNameAsString(), writeBuffer = actionWriter.getParameter(0).getNameAsString();
            var reads = block(actionReader); var writes = block(actionWriter);
            normalizeNullable(reads, readBuffer); normalizeNullable(writes, writeBuffer);
            for (var statement : new ArrayList<>(reads.getStatements())) if (statement instanceof ExpressionStmt e && e.getExpression() instanceof AssignExpr assignment && assignment.getOperator() == AssignExpr.Operator.ASSIGN && assignment.getTarget() instanceof FieldAccessExpr field && field.getScope().toString().equals(readValue)) {
                String originalType = Analyzer.declaredType(field.getNameAsString(), builderSource).replaceAll("@[A-Za-z0-9_.]+\\s*", "");
                e.setExpression(new VariableDeclarationExpr(new VariableDeclarator(StaticJavaParser.parseType(originalType), field.getNameAsString(), assignment.getValue().clone())));
            }
            List<Field> fields = new LinearWireProjection(analyzer).lowerBodies(reads, writes, readBuffer, writeBuffer, enumSource);
            String key = enumName + "#action-" + constant.getNameAsString(), name = analyzer.index.generated(enumName) + "_" + constant.getNameAsString();
            var model = new Model(key, name, fields, List.of(), false, List.of(), null, constant.toString(), ""); analyzer.models.put(key, model);
            cases.add(new DispatchCase(constant.getNameAsString(), modelWire(model)));
        }
        var enumModel = analyzer.model(enumName); Wire key = new Wire(enumModel.generatedName(), "WireCodecs.enumOrdinal(" + enumModel.generatedName() + ".values())", "enum:" + enumName);
        String table = "dispatch_" + Integer.toUnsignedString((enumName + "#action-set").hashCode());
        String entries = String.join(", ", cases.stream().map(c -> "java.util.Map.entry(" + key.type() + "." + c.key() + ", (PacketCodec<?>) " + c.value().codec() + ")").toList());
        Wire implementation = new Wire("org.stackanvil.packetforge.commons.Variant<" + key.type() + ">", "WireCodecs.dispatch(" + key.codec() + ", java.util.Map.<" + key.type() + ", PacketCodec<?>>ofEntries(" + entries + "))", "dispatch:" + table);
        analyzer.dispatches.put(table, new Dispatch(table, key, List.copyOf(cases), enumName, "enum-actions:" + readerField + ":" + writerField, implementation, "", ""));
        Wire mask = new Wire("java.util.Set<" + key.type() + ">", "ActionSetCodecs.mask(" + key.type() + ".values())", "enum-mask:" + enumName);
        Wire list = new Wire("java.util.List<org.stackanvil.packetforge.commons.ActionEntry<" + key.type() + ">>", "", "action-entries<dispatch:" + table + ">");
        String entryCodec = "WireCodecs.list(ActionSetCodecs.entry(actions, org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + table + "_payloads()), Integer.MAX_VALUE)";
        String decoder = "var actions = " + mask.codec() + ".decode(input);\nvar entries = " + entryCodec + ".decode(input);\nreturn new " + generated + "(actions, entries);";
        String encoder = "var actions = value.actions();\n" + mask.codec() + ".encode(output, actions);\n" + entryCodec + ".encode(output, value.entries());";
        return new ProgramLowerer.Lowered(List.of(new Field("actions", "EnumSet<" + enumName + ">", mask, mask), new Field("entries", "List<" + source.name() + ".Entry>", list, list)), new Program(decoder, encoder, List.of(mask.shape(), "UUID_CODEC", implementation.shape()), List.of()));
    }
    private void verifyBuilder(SourceIndex.Source builder) {
        var constructors = builder.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration).map(m -> (ConstructorDeclaration) m).toList();
        if (constructors.size() != 1 || constructors.getFirst().getParameters().size() != 1 || constructors.getFirst().getBody().getStatements().size() != 1 || !constructors.getFirst().getBody().getStatement(0).toString().equals("this.profileId = " + constructors.getFirst().getParameter(0).getNameAsString() + ";")) throw new Unsupported("Action entry builder transforms its identifier");
        var build = method(builder, "build", 0); var body = build.getBody().get().getStatements();
        if (body.size() != 1 || !(body.get(0) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof ObjectCreationExpr creation)) throw new Unsupported("Action builder has extra result operations");
        var entrySource = analyzer.index.source(analyzer.index.resolve(creation.getTypeAsString(), builder));
        if (!(entrySource.declaration() instanceof RecordDeclaration record) || creation.getArguments().size() != record.getParameters().size() || record.getMembers().stream().anyMatch(m -> m instanceof CompactConstructorDeclaration || m instanceof ConstructorDeclaration c && c.getParameters().size() == record.getParameters().size())) throw new Unsupported("Action result is not a direct wire record");
        for (int i = 0; i < record.getParameters().size(); i++) if (!creation.getArgument(i).toString().equals("this." + record.getParameter(i).getNameAsString()) || !record.getMethodsByName(record.getParameter(i).getNameAsString()).isEmpty()) throw new Unsupported("Action builder or record transforms its fields");
    }
    private String actionLoop(Statement statement, String actions, String enumeration, SourceIndex.Source source, String method, String first, String second) {
        if (!(statement instanceof ForEachStmt loop) || !loop.getIterable().toString().equals(actions) || loop.getVariable().getVariables().size() != 1 || !analyzer.index.resolve(loop.getVariable().getVariable(0).getTypeAsString(), source).equals(enumeration)) throw new Unsupported("Unknown action traversal order");
        Statement operation = loop.getBody(); if (operation instanceof BlockStmt block && block.getStatements().size() == 1) operation = block.getStatement(0);
        if (!(expression(operation) instanceof MethodCallExpr call) || !call.getNameAsString().equals(method) || !call.getArguments().stream().map(Object::toString).toList().equals(List.of(first, second)) || !(call.getScope().orElse(null) instanceof FieldAccessExpr field) || !field.getScope().toString().equals(loop.getVariable().getVariable(0).getNameAsString())) throw new Unsupported("Action loop has additional operations"); return field.getNameAsString();
    }
    private MethodCallExpr listFactory(Expression expression) {
        if (!(expression instanceof MethodCallExpr apply) || !apply.getNameAsString().equals("apply") || !apply.getArguments().toString().equals("[ByteBufCodecs.list()]") || !(apply.getScope().orElse(null) instanceof MethodCallExpr factory) || factory.getScope().isPresent() || !factory.getArguments().toString().equals("[this.actions]")) throw new Unsupported("Action entries are not a list bound to the packet mask"); return factory;
    }
    private void normalizeNullable(Node body, String buffer) {
        for (var call : new ArrayList<>(body.findAll(MethodCallExpr.class))) if (Set.of("readNullable", "writeNullable").contains(call.getNameAsString()) && call.getScope().map(Object::toString).orElse("").equals("FriendlyByteBuf") && !call.getArguments().isEmpty() && call.getArgument(0).toString().equals(buffer)) { call.getArguments().remove(0); call.setScope(new NameExpr(buffer)); }
    }
    private BlockStmt block(LambdaExpr lambda) { return lambda.getBody() instanceof BlockStmt block ? block.clone() : new BlockStmt().addStatement(lambda.getBody().clone()); }
    private MethodDeclaration method(SourceIndex.Source source, String name, int parameters) {
        var methods = source.declaration().getMethodsByName(name).stream().filter(m -> m.getParameters().size() == parameters && m.getBody().isPresent()).toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous action-set method: " + name); return methods.getFirst();
    }
    private Expression assignment(Statement statement, String field) {
        if (!(expression(statement) instanceof AssignExpr assigned) || assigned.getOperator() != AssignExpr.Operator.ASSIGN || !assigned.getTarget().toString().equals("this." + field)) throw new Unsupported("Unknown action-set field assignment"); return assigned.getValue();
    }
    private Expression expression(Statement statement) { if (!(statement instanceof ExpressionStmt expression)) throw new Unsupported("Unknown action-set statement"); return expression.getExpression(); }
}
