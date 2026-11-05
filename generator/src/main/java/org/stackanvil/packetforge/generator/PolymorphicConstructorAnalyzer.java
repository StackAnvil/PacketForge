package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Separates a shared header from enum-selected subclass constructor payloads. */
final class PolymorphicConstructorAnalyzer {
    private final Analyzer analyzer;
    PolymorphicConstructorAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    List<Field> lower(Expression codec, SourceIndex.Source source) {
        if (!(codec instanceof MethodCallExpr call) || !call.getNameAsString().equals("ofMember") || call.getArguments().size() != 2 || !(call.getArgument(0) instanceof MethodReferenceExpr writer) || !(call.getArgument(1) instanceof MethodReferenceExpr reader) || !analyzer.index.resolve(writer.getScope().toString(), source).equals(source.name()) || !analyzer.index.resolve(reader.getScope().toString(), source).equals(source.name())) throw new Unsupported("Not a polymorphic constructor codec");
        var read = method(source, reader.getIdentifier()); var write = method(source, writer.getIdentifier());
        if (!read.isStatic() || write.isStatic() || read.getBody().isEmpty() || write.getBody().isEmpty()) throw new Unsupported("Unknown polymorphic entry points");
        var decoded = read.getBody().get().getStatements(); var encoded = write.getBody().get().getStatements();
        if (decoded.size() < 3 || decoded.size() != encoded.size()) throw new Unsupported("Polymorphic header operation counts differ");
        String input = alias(decoded.get(0), read.getParameter(0).getNameAsString()), output = alias(encoded.get(0), write.getParameter(0).getNameAsString());
        int discriminator = decoded.size() - 2;
        var type = variable(decoded.get(discriminator));
        if (!(type.getInitializer().orElse(null) instanceof MethodCallExpr keyRead) || !keyRead.getScope().map(Object::toString).orElse("").equals(input) || !keyRead.getNameAsString().equals("readEnum") || keyRead.getArguments().size() != 1 || !(keyRead.getArgument(0) instanceof ClassExpr enumerationType)) throw new Unsupported("Polymorphic discriminator is not an ordinal enum");
        String enumName = analyzer.index.resolve(enumerationType.getTypeAsString(), source); var enumSource = analyzer.index.source(enumName);
        if (!(enumSource.declaration() instanceof EnumDeclaration enumeration)) throw new Unsupported("Polymorphic discriminator has no enum declaration");
        if (!(decoded.get(decoded.size() - 1) instanceof ReturnStmt returned)) throw new Unsupported("Missing polymorphic constructor result");
        Expression result = returned.getExpression().orElseThrow(() -> new Unsupported("Empty polymorphic result"));
        if (result instanceof CastExpr cast && analyzer.index.resolve(cast.getTypeAsString(), source).equals(source.name())) result = cast.getExpression();
        if (!(result instanceof MethodCallExpr factory) || !factory.getNameAsString().equals("apply") || !(factory.getScope().orElse(null) instanceof FieldAccessExpr constructorField) || !constructorField.getScope().toString().equals(type.getNameAsString())) throw new Unsupported("Unknown polymorphic enum constructor invocation");
        var keyWrite = invocation(encoded.get(discriminator));
        if (!keyWrite.getScope().map(Object::toString).orElse("").equals(output) || !keyWrite.getNameAsString().equals("writeEnum") || keyWrite.getArguments().size() != 1 || !(keyWrite.getArgument(0) instanceof FieldAccessExpr typeField) || !(typeField.getScope() instanceof ThisExpr)) throw new Unsupported("Polymorphic writer has a different discriminator");
        var contents = invocation(encoded.get(encoded.size() - 1));
        if (!contents.getScope().map(Object::toString).orElse("").equals("this") || contents.getArguments().size() != 1 || !contents.getArgument(0).toString().equals(write.getParameter(0).getNameAsString())) throw new Unsupported("Unknown polymorphic contents writer");
        var abstractWriter = method(source, contents.getNameAsString());
        if (!abstractWriter.isAbstract() || abstractWriter.getBody().isPresent()) throw new Unsupported("Polymorphic contents writer has additional behavior");
        List<Field> fields = new ArrayList<>(); List<String> header = new ArrayList<>();
        for (int i = 1; i < discriminator; i++) {
            var value = variable(decoded.get(i)); String name = value.getNameAsString(); header.add(name);
            Wire wire = analyzer.read(value.getInitializer().orElseThrow(() -> new Unsupported("Uninitialized polymorphic header")), input, source);
            Wire written = analyzer.write(invocation(encoded.get(i)), output, name, wire, source);
            if (!wire.type().equals(written.type()) || !wire.shape().equals(written.shape())) throw new Unsupported("Polymorphic header wire mismatch");
            fields.add(new Field(name, value.getTypeAsString(), wire, written));
        }
        var arguments = new ArrayList<>(header); arguments.add(input);
        if (!factory.getArguments().stream().map(Object::toString).toList().equals(arguments)) throw new Unsupported("Polymorphic factory transforms its header arguments");
        var constructors = enumeration.getConstructors();
        if (constructors.size() != 1 || constructors.getFirst().getParameters().size() != 1 || constructors.getFirst().getBody().getStatements().size() != 1 || !constructors.getFirst().getBody().getStatement(0).toString().equals("this." + constructorField.getNameAsString() + " = " + constructors.getFirst().getParameter(0).getNameAsString() + ";")) throw new Unsupported("Enum transforms its constructor function");
        var baseConstructors = source.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration c && c.getParameters().size() == header.size() + 1).map(m -> (ConstructorDeclaration) m).toList();
        if (baseConstructors.size() != 1) throw new Unsupported("Ambiguous polymorphic base constructor");
        var base = baseConstructors.getFirst(); List<String> baseFields = new ArrayList<>(header); baseFields.add(typeField.getNameAsString());
        if (base.getBody().getStatements().size() != baseFields.size()) throw new Unsupported("Polymorphic base constructor has extra behavior");
        for (int i = 0; i < baseFields.size(); i++) if (!base.getBody().getStatement(i).toString().equals("this." + baseFields.get(i) + " = " + base.getParameter(i).getNameAsString() + ";")) throw new Unsupported("Polymorphic base constructor transforms its fields");
        List<DispatchCase> cases = new ArrayList<>();
        for (var constant : enumeration.getEntries()) {
            if (constant.getArguments().size() != 1 || !(constant.getArgument(0) instanceof MethodReferenceExpr reference) || !reference.getIdentifier().equals("new")) throw new Unsupported("Unknown polymorphic enum constructor");
            String owner = analyzer.index.resolve(reference.getScope().toString(), enumSource); var subtype = analyzer.index.source(owner);
            if (!(subtype.declaration() instanceof ClassOrInterfaceDeclaration subclass) || subclass.getExtendedTypes().size() != 1 || !analyzer.index.resolve(subclass.getExtendedTypes().get(0).getNameWithScope(), subtype).equals(source.name())) throw new Unsupported("Enum constructor does not extend its base value");
            var candidates = subclass.getConstructors().stream().filter(c -> c.getParameters().size() == header.size() + 1 && c.getParameter(header.size()).getTypeAsString().endsWith("ByteBuf")).toList();
            if (candidates.size() != 1) throw new Unsupported("Ambiguous polymorphic buffer constructor");
            var constructor = candidates.getFirst(); var body = constructor.getBody().clone();
            if (body.isEmpty() || !(body.getStatement(0) instanceof ExplicitConstructorInvocationStmt parent) || parent.isThis() || parent.getArguments().size() != baseFields.size()) throw new Unsupported("Missing polymorphic superclass initialization");
            for (int i = 0; i < header.size(); i++) if (!parent.getArgument(i).toString().equals(constructor.getParameter(i).getNameAsString())) throw new Unsupported("Subclass transforms its shared header");
            if (!(parent.getArgument(header.size()) instanceof FieldAccessExpr tag) || !tag.getNameAsString().equals(constant.getNameAsString()) || !analyzer.index.resolve(tag.getScope().toString(), subtype).equals(enumName)) throw new Unsupported("Subclass discriminator differs from its constructor table entry");
            body.getStatements().remove(0);
            for (int i = 0; i < header.size(); i++) { String parameter = constructor.getParameter(i).getNameAsString(); if (body.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(parameter))) throw new Unsupported("Subclass payload depends on shared header values"); }
            var payloadWriter = method(subtype, contents.getNameAsString());
            if (payloadWriter.getBody().isEmpty()) throw new Unsupported("Subclass has no contents writer");
            Wire payload;
            if (body.isEmpty() && payloadWriter.getBody().get().isEmpty()) payload = new Wire("org.stackanvil.packetforge.commons.Unit", "WireCodecs.UNIT", "UNIT");
            else {
                String modelKey = owner + "#contents", generated = analyzer.index.generated(owner) + "_Contents";
                var lowered = new ProgramLowerer(analyzer, subtype, generated).lowerBodies(body, payloadWriter.getBody().get(), constructor.getParameter(header.size()).getNameAsString(), payloadWriter.getParameter(0).getNameAsString(), true);
                Model model = new Model(modelKey, generated, lowered.fields(), List.of(), false, List.of(), lowered.program(), reference.toString(), ""); analyzer.models.put(modelKey, model); payload = modelWire(model);
            }
            cases.add(new DispatchCase(constant.getNameAsString(), payload, owner));
        }
        Wire key = analyzer.read(keyRead, input, source); String name = "dispatch_" + Integer.toUnsignedString((enumName + "#constructors").hashCode());
        String entries = String.join(", ", cases.stream().map(c -> "java.util.Map.entry(" + key.type() + "." + c.key() + ", (PacketCodec<?>) " + c.value().codec() + ")").toList());
        Wire implementation = new Wire("org.stackanvil.packetforge.commons.Variant<" + key.type() + ">", "WireCodecs.dispatch(" + key.codec() + ", java.util.Map.<" + key.type() + ", PacketCodec<?>>ofEntries(" + entries + "))", "dispatch:" + name);
        analyzer.dispatches.put(name, new Dispatch(name, key, List.copyOf(cases), enumName, "enum-constructor:" + constructorField.getNameAsString() + ":" + contents.getNameAsString(), implementation, "", ""));
        Wire wire = new Wire(implementation.type(), "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name, implementation.shape());
        fields.add(new Field("contents", source.name(), wire, wire)); return List.copyOf(fields);
    }
    private MethodDeclaration method(SourceIndex.Source source, String name) {
        var methods = source.declaration().getMethodsByName(name).stream().filter(m -> m.getParameters().size() == 1 && m.getParameter(0).getTypeAsString().endsWith("ByteBuf")).toList();
        if (methods.size() != 1) throw new Unsupported("Ambiguous polymorphic method: " + name); return methods.getFirst();
    }
    private VariableDeclarator variable(Statement statement) {
        if (!(statement instanceof ExpressionStmt e) || !(e.getExpression() instanceof VariableDeclarationExpr variables) || variables.getVariables().size() != 1) throw new Unsupported("Expected a polymorphic header local"); return variables.getVariable(0);
    }
    private MethodCallExpr invocation(Statement statement) {
        if (!(statement instanceof ExpressionStmt e) || !(e.getExpression() instanceof MethodCallExpr call)) throw new Unsupported("Expected a polymorphic writer call"); return call;
    }
    private String alias(Statement statement, String buffer) {
        var variable = variable(statement);
        if (!variable.getTypeAsString().equals("FriendlyByteBuf") || !(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr created) || !created.getTypeAsString().equals("FriendlyByteBuf") || created.getArguments().size() != 1 || !created.getArgument(0).toString().equals(buffer)) throw new Unsupported("Unknown buffer wrapper in polymorphic codec"); return variable.getNameAsString();
    }
}
