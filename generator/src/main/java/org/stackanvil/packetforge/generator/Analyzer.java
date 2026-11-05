package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.Node;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Lowers a deliberately bounded AST language to independent wire codecs. */
final class Analyzer {
    record Wire(String type, String codec, String shape) {}
    record Field(String name, String originalType, Wire read, Wire write) {}
    record Model(String originalName, String generatedName, List<Field> fields, List<String> constants, boolean packet, List<String> validations) {
        Model(String originalName, String generatedName, List<Field> fields, List<String> constants, boolean packet) { this(originalName, generatedName, fields, constants, packet, List.of()); }
    }
    record Registration(String state, String flow, int id, String name, String originalClass, String codecExpression,
                        String source, int line, boolean supported, String reason, String generatedClass) {}
    private record Decoded(String name, Expression value) {}
    private static final Map<String, String> SCALARS = Map.ofEntries(
        Map.entry("Boolean", "BOOL:boolean"), Map.entry("Byte", "BYTE:byte"), Map.entry("UnsignedByte", "UNSIGNED_BYTE:int"),
        Map.entry("Short", "SHORT:short"), Map.entry("UnsignedShort", "UNSIGNED_SHORT:int"), Map.entry("Int", "INT:int"),
        Map.entry("Long", "LONG:long"), Map.entry("Float", "FLOAT:float"), Map.entry("Double", "DOUBLE:double"),
        Map.entry("VarInt", "VAR_INT:int"), Map.entry("ContainerId", "VAR_INT:int"), Map.entry("VarLong", "VAR_LONG:long"),
        Map.entry("LongArray", "LONG_ARRAY:long[]"), Map.entry("VarIntArray", "VAR_INT_ARRAY:int[]"),
        Map.entry("ChunkPos", "CHUNK_POSITION:org.stackanvil.packetforge.commons.ChunkPosition"),
        Map.entry("UUID", "UUID_CODEC:java.util.UUID"), Map.entry("Identifier", "IDENTIFIER:String"), Map.entry("BlockPos", "BLOCK_POSITION:org.stackanvil.packetforge.commons.BlockPosition"));
    private static final Map<String, String> CODEC_SCALARS = Map.ofEntries(
        Map.entry("BOOL", "Boolean"), Map.entry("BYTE", "Byte"), Map.entry("SHORT", "Short"), Map.entry("UNSIGNED_SHORT", "UnsignedShort"),
        Map.entry("INT", "Int"), Map.entry("LONG", "Long"), Map.entry("FLOAT", "Float"), Map.entry("DOUBLE", "Double"),
        Map.entry("VAR_INT", "VarInt"), Map.entry("VAR_LONG", "VarLong"), Map.entry("CONTAINER_ID", "ContainerId"));
    private final SourceIndex index;
    private final Path root;
    final Map<String, Model> models = new TreeMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final Map<String, List<String>> validations = new HashMap<>();
    Analyzer(Path sources) { root = sources; index = new SourceIndex(sources); }

    List<Registration> analyze() throws IOException {
        List<Registration> registrations = new ArrayList<>();
        try (var files = Files.walk(root.resolve("net/minecraft/network/protocol"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("Protocols.java")).sorted().toList()) {
                String fqn = root.relativize(file).toString().replace(java.io.File.separatorChar, '.').replaceAll("\\.java$", "");
                var source = index.source(fqn);
                for (FieldDeclaration field : source.declaration().getFields()) for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getInitializer().isEmpty() || !(variable.getInitializer().get() instanceof MethodCallExpr call)) continue;
                    String method = call.getNameAsString();
                    if (!Set.of("serverboundProtocol", "clientboundProtocol", "contextServerboundProtocol", "contextClientboundProtocol").contains(method)) continue;
                    if (call.getArguments().size() != 2 || !(call.getArgument(0) instanceof FieldAccessExpr state)) throw new Unsupported("Unknown protocol builder: " + call);
                    String flow = method.toLowerCase(Locale.ROOT).contains("serverbound") ? "SERVERBOUND" : "CLIENTBOUND";
                    LambdaExpr lambda = call.getArgument(1).asLambdaExpr();
                    String builder = lambda.getParameter(0).getNameAsString();
                    if (!(lambda.getBody() instanceof ExpressionStmt expression)) throw new Unsupported("Protocol builder is not a fluent expression");
                    List<MethodCallExpr> chain = new ArrayList<>();
                    collectChain(expression.getExpression(), builder, chain);
                    int id = 0;
                    for (MethodCallExpr registration : chain) {
                        if (registration.getNameAsString().equals("withBundlePacket")) {
                            var delimiter = registration.getArgument(2).asObjectCreationExpr();
                            String name = index.resolve(delimiter.getType().asString(), source);
                            var delimiterSource = index.source(name);
                            String packetName = packetName(delimiterSource);
                            Model model = new Model(name, index.generated(name), List.of(), List.of(), true);
                            models.put(name, model);
                            registrations.add(new Registration(state.getNameAsString(), flow, id++, packetName, name, "bundle delimiter", fqn, registration.getBegin().orElseThrow().line, true, "", model.generatedName()));
                            continue;
                        }
                        if (!registration.getNameAsString().equals("addPacket")) throw new Unsupported("Unknown registration operation: " + registration.getNameAsString());
                        String key = registration.getArgument(0).toString();
                        Expression codec = registration.getArgument(1);
                        if (!(codec instanceof FieldAccessExpr access)) throw new Unsupported("Registration codec is not a field: " + codec);
                        String className = index.resolve(access.getScope().toString(), source);
                        String packetName = packetName(key, source);
                        String reason = ""; Model model = null;
                        try {
                            if (registration.getArguments().size() != 2) throw new Unsupported("Context-dependent codec modifier");
                            if (!access.getNameAsString().equals("STREAM_CODEC")) throw new Unsupported("Non-default packet codec: " + codec);
                            model = model(className);
                            if (!model.packet()) throw new Unsupported("Registered type does not directly implement Packet");
                        } catch (Unsupported e) { reason = e.getMessage(); }
                        registrations.add(new Registration(state.getNameAsString(), flow, id++, packetName, className, codec.toString(), fqn,
                            registration.getBegin().orElseThrow().line, reason.isEmpty(), reason, reason.isEmpty() ? model.generatedName() : ""));
                    }
                }
            }
        }
        if (registrations.isEmpty()) throw new Unsupported("No protocol registrations found");
        Set<String> keys = new HashSet<>();
        for (var registration : registrations) if (!keys.add(registration.state() + ":" + registration.flow() + ":" + registration.id())) throw new Unsupported("Duplicate protocol registration");
        return List.copyOf(registrations);
    }
    private void collectChain(Expression expression, String builder, List<MethodCallExpr> chain) {
        if (expression instanceof NameExpr name && name.getNameAsString().equals(builder)) return;
        if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty()) throw new Unsupported("Unknown builder expression: " + expression);
        collectChain(call.getScope().get(), builder, chain);
        chain.add(call);
    }
    private String packetName(SourceIndex.Source source) {
        var method = source.declaration().getMethodsByName("type").getFirst();
        var value = method.getBody().orElseThrow().getStatement(0).asReturnStmt().getExpression().orElseThrow();
        return packetName(value.toString(), source);
    }
    private String packetName(String key, SourceIndex.Source context) {
        int dot = key.lastIndexOf('.');
        var source = index.source(index.resolve(key.substring(0, dot), context));
        Expression initializer = source.declaration().getFieldByName(key.substring(dot + 1)).orElseThrow().getVariable(0).getInitializer().orElseThrow();
        if (!(initializer instanceof MethodCallExpr call) || call.getArguments().size() != 1 || !(call.getArgument(0) instanceof StringLiteralExpr id)) throw new Unsupported("Unknown packet type declaration: " + key);
        return "minecraft:" + id.asString();
    }

    Model model(String name) {
        if (models.containsKey(name)) return models.get(name);
        if (failures.containsKey(name)) throw new Unsupported(failures.get(name));
        if (!active.add(name)) throw new Unsupported("Recursive codec dependency: " + name);
        try {
            var source = index.source(name);
            var declaration = source.declaration();
            if (declaration instanceof EnumDeclaration enumeration) {
                Model model = new Model(name, index.generated(name), List.of(), enumeration.getEntries().stream().map(EnumConstantDeclaration::getNameAsString).toList(), false);
                if (model.constants().isEmpty()) throw new Unsupported("Empty enum: " + name);
                models.put(name, model); return model;
            }
            boolean packet = declaration instanceof RecordDeclaration record ? record.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("Packet"))
                : declaration instanceof ClassOrInterfaceDeclaration clazz && clazz.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("Packet"));
            if (declaration instanceof ClassOrInterfaceDeclaration clazz && !clazz.getExtendedTypes().isEmpty()) throw new Unsupported("Inherited packet/value layout: " + name);
            Expression codec = declaration.getFieldByName("STREAM_CODEC").map(f -> f.getVariable(0).getInitializer().orElseThrow())
                .orElseGet(() -> StaticJavaParser.parseExpression("Packet.codec(" + declaration.getNameAsString() + "::write, " + declaration.getNameAsString() + "::new)"));
            List<Field> fields;
            if (codec instanceof MethodCallExpr call && call.getScope().map(Object::toString).orElse("").equals("StreamCodec") && call.getNameAsString().equals("unit")) {
                fields = List.of();
            } else if (codec instanceof MethodCallExpr call && call.getScope().map(Object::toString).orElse("").equals("StreamCodec") && call.getNameAsString().equals("composite")) {
                fields = composite(call, source);
            } else if (codec instanceof MethodCallExpr call && Set.of("Packet", "StreamCodec").contains(call.getScope().map(Object::toString).orElse("")) && Set.of("codec", "ofMember").contains(call.getNameAsString())) {
                fields = memberCodec(call, source);
            } else if (codec instanceof MethodCallExpr call && call.getNameAsString().equals("map") && call.getArguments().size() == 2 && call.getArgument(0) instanceof MethodReferenceExpr constructor && constructor.getIdentifier().equals("new") && index.resolve(constructor.getScope().toString(), source).equals(name)) {
                String field = getterField(call.getArgument(1), source);
                Wire underlying = codec(call.getScope().orElseThrow(), source);
                fields = List.of(new Field(field, declaredType(field, source), underlying, underlying));
            } else if (codec instanceof NameExpr || codec instanceof FieldAccessExpr) {
                Wire underlying = codec(codec, source);
                if (underlying.shape().startsWith("model:")) fields = models.get(underlying.shape().substring(6)).fields();
                else throw new Unsupported("Codec alias does not construct this value");
            } else { throw new Unsupported("Unsupported codec declaration: " + shortText(codec)); }
            Model model = new Model(name, index.generated(name), fields, List.of(), packet, validations.getOrDefault(name, List.of()));
            models.put(name, model); return model;
        } catch (Unsupported e) { failures.put(name, e.getMessage()); throw e; }
        finally { active.remove(name); }
    }
    private List<Field> composite(MethodCallExpr call, SourceIndex.Source source) {
        var args = call.getArguments();
        if (args.isEmpty() || args.size() % 2 != 1 || !(args.get(args.size() - 1) instanceof MethodReferenceExpr constructor) || !index.resolve(constructor.getScope().toString(), source).equals(source.name())) throw new Unsupported("Composite does not end in its constructor or factory");
        if (!constructor.getIdentifier().equals("new")) {
            var factories = source.declaration().getMethodsByName(constructor.getIdentifier());
            if (factories.size() != 1 || !factories.getFirst().isStatic() || factories.getFirst().getBody().isEmpty()) throw new Unsupported("Unknown composite factory");
            var body = factories.getFirst().getBody().get();
            if (body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof ObjectCreationExpr created) || !created.getArguments().stream().map(Object::toString).toList().equals(factories.getFirst().getParameters().stream().map(p -> p.getNameAsString()).toList())) throw new Unsupported("Composite factory transforms its arguments");
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < args.size() - 1; i += 2) {
            String name = getterField(args.get(i + 1), source);
            String fieldType = declaredType(name, source);
            Wire wire = codec(args.get(i), source);
            fields.add(new Field(name, fieldType, wire, wire));
        }
        if (source.declaration() instanceof RecordDeclaration record && !record.getParameters().stream().map(p -> p.getNameAsString()).toList().equals(fields.stream().map(Field::name).toList())) throw new Unsupported("Composite getter order differs from record constructor");
        return fields;
    }
    private List<Field> memberCodec(MethodCallExpr call, SourceIndex.Source source) {
        if (call.getArguments().size() != 2 || !(call.getArgument(0) instanceof MethodReferenceExpr writer) || !(call.getArgument(1) instanceof MethodReferenceExpr reader)) throw new Unsupported("Codec is not a writer/constructor pair");
        if (!reader.getIdentifier().equals("new") || !index.resolve(reader.getScope().toString(), source).equals(source.name()) || !index.resolve(writer.getScope().toString(), source).equals(source.name())) throw new Unsupported("Codec reader is not its constructor");
        var constructors = source.declaration().getMembers().stream().filter(x -> x instanceof ConstructorDeclaration c && c.getParameters().size() == 1 && isBuffer(c.getParameter(0).getTypeAsString())).map(x -> (ConstructorDeclaration) x).toList();
        if (constructors.size() != 1) throw new Unsupported("Expected one buffer constructor");
        var constructor = constructors.getFirst();
        String input = constructor.getParameter(0).getNameAsString();
        List<Decoded> decoded = new ArrayList<>();
        var statements = new ArrayList<>(constructor.getBody().getStatements());
        List<String> guards = new ArrayList<>();
        while (!statements.isEmpty() && statements.getLast() instanceof IfStmt guard && guard.getElseStmt().isEmpty()) {
            var thrown = guard.getThenStmt().isBlockStmt() && guard.getThenStmt().asBlockStmt().getStatements().size() == 1 ? guard.getThenStmt().asBlockStmt().getStatement(0) : guard.getThenStmt();
            if (!(thrown instanceof ThrowStmt throwing) || !(throwing.getExpression() instanceof ObjectCreationExpr creation) || !creation.getTypeAsString().equals("IllegalArgumentException") || !guard.findAll(MethodCallExpr.class).isEmpty()) break;
            IfStmt copy = guard.clone();
            for (var access : new ArrayList<>(copy.findAll(FieldAccessExpr.class))) if (access.getScope() instanceof ThisExpr) access.replace(new NameExpr(access.getNameAsString()));
            guards.addFirst(copy.toString()); statements.removeLast();
        }
        validations.put(source.name(), List.copyOf(guards));
        if (statements.size() == 1 && statements.get(0) instanceof ExplicitConstructorInvocationStmt invocation && invocation.isThis() && source.declaration() instanceof RecordDeclaration record) {
            if (invocation.getArguments().size() != record.getParameters().size()) throw new Unsupported("Record constructor arity mismatch");
            for (int i = 0; i < record.getParameters().size(); i++) decoded.add(new Decoded(record.getParameter(i).getNameAsString(), invocation.getArgument(i)));
        } else {
            for (Statement statement : statements) {
                if (!(statement instanceof ExpressionStmt expr) || !(expr.getExpression() instanceof AssignExpr assignment) || assignment.getOperator() != AssignExpr.Operator.ASSIGN) throw new Unsupported("Reader has control flow or intermediate operations: " + shortText(statement));
                decoded.add(new Decoded(directField(assignment.getTarget()), assignment.getValue()));
            }
        }
        List<MethodDeclaration> writers = source.declaration().getMethodsByName(writer.getIdentifier()).stream().filter(m -> m.getParameters().size() == 1 && isBuffer(m.getParameter(0).getTypeAsString())).toList();
        if (writers.size() != 1) throw new Unsupported("Expected one buffer writer");
        var method = writers.getFirst();
        String output = method.getParameter(0).getNameAsString();
        List<Field> fields = new ArrayList<>();
        var writes = method.getBody().orElseThrow().getStatements();
        if (writes.size() != decoded.size()) throw new Unsupported("Reader/writer operation counts differ");
        for (int i = 0; i < writes.size(); i++) {
            Decoded read = decoded.get(i);
            if (!(writes.get(i) instanceof ExpressionStmt expr) || !(expr.getExpression() instanceof MethodCallExpr write)) throw new Unsupported("Writer has control flow or intermediate operations");
            Wire readWire = read(read.value(), input, source);
            Wire writeWire = write(write, output, read.name(), readWire, source);
            if (!readWire.shape().equals(writeWire.shape()) || !readWire.type().equals(writeWire.type())) throw new Unsupported("Reader/writer wire types differ for " + read.name());
            fields.add(new Field(read.name(), declaredType(read.name(), source), readWire, writeWire));
        }
        Set<String> names = new HashSet<>();
        for (Field field : fields) if (!names.add(field.name())) throw new Unsupported("Repeated field assignment");
        if (source.declaration() instanceof RecordDeclaration record && record.getParameters().size() != fields.size()) throw new Unsupported("Record contains fields outside its wire layout");
        return fields;
    }
    private Wire read(Expression expression, String buffer, SourceIndex.Source source) {
        if (expression instanceof CastExpr cast) {
            if (cast.getType().isPrimitiveType()) throw new Unsupported("Numeric cast changes wire value: " + shortText(expression));
            return read(cast.getExpression(), buffer, source);
        }
        if (expression instanceof ObjectCreationExpr creation && creation.getArguments().size() == 1 && creation.getArgument(0).toString().equals(buffer)) return modelWire(model(index.resolve(creation.getTypeAsString(), source)));
        if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty()) throw new Unsupported("Unsupported read expression: " + shortText(expression));
        if (call.getNameAsString().equals("decode") && call.getArguments().size() == 1 && call.getArgument(0).toString().equals(buffer)) return codec(call.getScope().get(), source);
        if (!call.getScope().get().toString().equals(buffer) && call.getArguments().size() == 1 && call.getArgument(0) instanceof MethodCallExpr nested && nested.getScope().map(Object::toString).orElse("").equals(buffer)) {
            return enumSwitch(call, read(call.getArgument(0), buffer, source), source);
        }
        if (!call.getScope().get().toString().equals(buffer) || !call.getNameAsString().startsWith("read")) throw new Unsupported("Read invokes a helper or transform: " + shortText(call));
        String operation = call.getNameAsString().substring(4);
        if (SCALARS.containsKey(operation) && call.getArguments().isEmpty()) return scalar(operation);
        if (operation.equals("Utf") && call.getArguments().size() <= 1) return string(call.getArguments().isEmpty() ? "32767" : integer(call.getArgument(0), source));
        if (operation.equals("ByteArray") && call.getArguments().size() <= 1) return new Wire("byte[]", "WireCodecs.byteArray(" + (call.getArguments().isEmpty() ? "Integer.MAX_VALUE" : integer(call.getArgument(0), source)) + ")", "byteArray");
        if (operation.equals("Enum") && call.getArguments().size() == 1 && call.getArgument(0) instanceof ClassExpr type) {
            Model enumeration = model(index.resolve(type.getTypeAsString(), source));
            if (enumeration.constants().isEmpty()) throw new Unsupported("readEnum target is not an enum");
            return new Wire(enumeration.generatedName(), "WireCodecs.enumOrdinal(" + enumeration.generatedName() + ".values())", "enum:" + enumeration.originalName());
        }
        if (Set.of("Nullable", "Optional", "List").contains(operation) && call.getArguments().size() == 1) {
            Wire inner = readerFunction(call.getArgument(0), source);
            return container(operation.toLowerCase(Locale.ROOT), inner, "Integer.MAX_VALUE");
        }
        throw new Unsupported("Unsupported buffer read: " + shortText(call));
    }
    private Wire write(MethodCallExpr call, String buffer, String field, Wire read, SourceIndex.Source source) {
        if (call.getScope().isEmpty()) throw new Unsupported("Unscoped write");
        if (call.getNameAsString().equals("write") && call.getArguments().size() == 1 && call.getArgument(0).toString().equals(buffer) && directField(call.getScope().get()).equals(field) && read.shape().startsWith("model:")) return read;
        if (call.getNameAsString().equals("encode") && call.getArguments().size() == 2 && call.getArgument(0).toString().equals(buffer) && directField(call.getArgument(1)).equals(field)) return codec(call.getScope().get(), source);
        if (read.shape().startsWith("enumSwitch:") && call.getScope().get().toString().equals(buffer) && call.getArguments().size() == 1 && call.getArgument(0) instanceof MethodCallExpr accessor && accessor.getArguments().isEmpty() && accessor.getScope().isPresent() && directField(accessor.getScope().get()).equals(field)) {
            String[] parts = read.shape().split(":");
            String operation = call.getNameAsString().replaceFirst("^write", "");
            if (accessor.getNameAsString().equals(parts[2]) && SCALARS.containsKey(operation) && scalar(operation).shape().equals(parts[3])) return read;
            throw new Unsupported("Enum ID reader/writer mapping differs");
        }
        if (!call.getScope().get().toString().equals(buffer) || !call.getNameAsString().startsWith("write") || call.getArguments().isEmpty() || !directField(call.getArgument(0)).equals(field)) throw new Unsupported("Writer does not serialize the matching field directly: " + shortText(call));
        String operation = call.getNameAsString().substring(5);
        if (SCALARS.containsKey(operation) && call.getArguments().size() == 1) {
            Wire scalar = scalar(operation);
            // Netty writes signed and unsigned values with the same width.
            if (operation.equals("Byte") && read.shape().equals("UNSIGNED_BYTE")) return new Wire("int", "WireCodecs.UNSIGNED_BYTE", "UNSIGNED_BYTE");
            if (operation.equals("Short") && read.shape().equals("UNSIGNED_SHORT")) return new Wire("int", "WireCodecs.UNSIGNED_SHORT", "UNSIGNED_SHORT");
            return scalar;
        }
        if (operation.equals("Utf") && call.getArguments().size() <= 2) return string(call.getArguments().size() == 1 ? "32767" : integer(call.getArgument(1), source));
        if (operation.equals("Enum") && call.getArguments().size() == 1 && read.shape().startsWith("enum:")) return read;
        if (operation.equals("ByteArray") && call.getArguments().size() == 1) return new Wire("byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
        if (Set.of("Nullable", "Optional", "Collection").contains(operation) && call.getArguments().size() == 2) {
            Wire inner = writerFunction(call.getArgument(1), source);
            return container(operation.equals("Collection") ? "list" : operation.toLowerCase(Locale.ROOT), inner, "Integer.MAX_VALUE");
        }
        throw new Unsupported("Unsupported buffer write: " + shortText(call));
    }
    private Wire enumIdMapper(MethodCallExpr call, SourceIndex.Source source) {
        String idField = enumIdField(call.getArgument(1), source);
        if (!(call.getArgument(0) instanceof NameExpr byId)) throw new Unsupported("Enum ID lookup is not a local constant");
        Expression initializer = source.declaration().getFieldByName(byId.getNameAsString()).orElseThrow().getVariable(0).getInitializer().orElseThrow();
        if (!(initializer instanceof MethodCallExpr continuous) || !continuous.getScope().map(Object::toString).orElse("").equals("ByIdMap") || !continuous.getNameAsString().equals("continuous") || continuous.getArguments().size() != 3 || !enumIdField(continuous.getArgument(0), source).equals(idField) || !continuous.getArgument(1).toString().equals("values()")) throw new Unsupported("Enum ID lookup is not a matching continuous map");
        String policy = continuous.getArgument(2).toString();
        if (!Set.of("ByIdMap.OutOfBoundsStrategy.ZERO", "ByIdMap.OutOfBoundsStrategy.WRAP", "ByIdMap.OutOfBoundsStrategy.CLAMP").contains(policy)) throw new Unsupported("Unknown enum ID fallback");
        var constructors = source.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration).map(m -> (ConstructorDeclaration) m).toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous enum constructor");
        var constructor = constructors.getFirst();
        String parameter = null;
        for (var assignment : constructor.findAll(AssignExpr.class)) {
            if (assignment.getTarget() instanceof FieldAccessExpr target && target.getScope() instanceof ThisExpr && target.getNameAsString().equals(idField)) {
                if (parameter != null || assignment.getOperator() != AssignExpr.Operator.ASSIGN || !(assignment.getValue() instanceof NameExpr name)) throw new Unsupported("Enum ID constructor transforms its value");
                parameter = name.getNameAsString();
            }
        }
        if (parameter == null) throw new Unsupported("Cannot find enum ID constructor parameter");
        int position = -1;
        for (int i = 0; i < constructor.getParameters().size(); i++) if (constructor.getParameter(i).getNameAsString().equals(parameter)) position = i;
        if (position < 0 || !constructor.getParameter(position).getTypeAsString().equals("int")) throw new Unsupported("Enum ID is not an integer parameter");
        List<String> ids = new ArrayList<>();
        for (var constant : ((EnumDeclaration) source.declaration()).getEntries()) ids.add(integer(constant.getArgument(position), source));
        if (!ids.stream().map(Integer::parseInt).sorted().toList().equals(java.util.stream.IntStream.range(0, ids.size()).boxed().toList())) throw new Unsupported("Enum IDs are not continuous");
        Model enumeration = model(source.name());
        return new Wire(enumeration.generatedName(), "WireCodecs.enumIds(" + enumeration.generatedName() + ".values(), new int[]{" + String.join(", ", ids) + "}, WireCodecs.VAR_INT, WireCodecs.EnumFallback." + policy.substring(policy.lastIndexOf('.') + 1) + ")", "enum:" + source.name());
    }
    private String enumIdField(Expression expression, SourceIndex.Source source) {
        Expression value;
        String receiver;
        if (expression instanceof LambdaExpr lambda && lambda.getParameters().size() == 1 && lambda.getBody() instanceof ExpressionStmt body) {
            value = body.getExpression(); receiver = lambda.getParameter(0).getNameAsString();
        } else if (expression instanceof MethodReferenceExpr reference && index.resolve(reference.getScope().toString(), source).equals(source.name())) {
            var methods = source.declaration().getMethodsByName(reference.getIdentifier());
            if (methods.size() != 1 || !methods.getFirst().getParameters().isEmpty()) throw new Unsupported("Ambiguous enum ID accessor");
            var body = methods.getFirst().getBody().orElseThrow();
            if (body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ReturnStmt returned)) throw new Unsupported("Enum ID accessor is not a direct field");
            value = returned.getExpression().orElseThrow(); receiver = "this";
        } else throw new Unsupported("Unknown enum ID accessor");
        if (!(value instanceof FieldAccessExpr field) || !field.getScope().toString().equals(receiver)) throw new Unsupported("Enum ID accessor transforms its field");
        var declaration = source.declaration().getFieldByName(field.getNameAsString()).orElseThrow();
        if (!declaration.isFinal() || !declaration.getVariable(0).getTypeAsString().equals("int")) throw new Unsupported("Enum ID is not a final integer");
        return field.getNameAsString();
    }
    private Wire enumSwitch(MethodCallExpr call, Wire inner, SourceIndex.Source context) {
        var source = index.source(index.resolve(call.getScope().orElseThrow().toString(), context));
        if (!(source.declaration() instanceof EnumDeclaration)) throw new Unsupported("Read helper is not an enum mapping");
        var readers = source.declaration().getMethodsByName(call.getNameAsString());
        if (readers.size() != 1 || readers.getFirst().getParameters().size() != 1) throw new Unsupported("Ambiguous enum ID reader");
        var reader = readers.getFirst();
        SwitchExpr readSwitch = returnedSwitch(reader);
        if (!readSwitch.getSelector().toString().equals(reader.getParameter(0).getNameAsString())) throw new Unsupported("Enum reader switches on another value");
        Map<String, String> ids = new HashMap<>();
        boolean rejectsUnknown = false;
        for (SwitchEntry entry : readSwitch.getEntries()) {
            if (entry.getLabels().isEmpty()) {
                rejectsUnknown = entry.getStatements().size() == 1 && entry.getStatement(0) instanceof ThrowStmt;
                continue;
            }
            if (entry.getLabels().size() != 1 || entry.getStatements().size() != 1 || !(entry.getStatement(0) instanceof ExpressionStmt value) || !(value.getExpression() instanceof NameExpr name)) throw new Unsupported("Complex enum ID reader");
            if (ids.put(name.getNameAsString(), integer(entry.getLabels().get(0), source)) != null) throw new Unsupported("Enum has duplicate wire IDs");
        }
        Model enumeration = model(source.name());
        if (!rejectsUnknown || !ids.keySet().equals(new HashSet<>(enumeration.constants())) || new HashSet<>(ids.values()).size() != ids.size()) throw new Unsupported("Enum reader is not a one-to-one mapping");
        for (var method : source.declaration().getMethods()) {
            if (!method.getParameters().isEmpty() || !method.getTypeAsString().equals("int")) continue;
            SwitchExpr writeSwitch;
            try { writeSwitch = returnedSwitch(method); } catch (Unsupported e) { continue; }
            if (!(writeSwitch.getSelector() instanceof ThisExpr)) continue;
            Map<String, String> encoded = new HashMap<>();
            for (SwitchEntry entry : writeSwitch.getEntries()) {
                if (entry.getLabels().size() != 1 || entry.getStatements().size() != 1 || !(entry.getLabels().get(0) instanceof NameExpr label) || !(entry.getStatement(0) instanceof ExpressionStmt value)) throw new Unsupported("Complex enum ID writer");
                encoded.put(label.getNameAsString(), integer(value.getExpression(), source));
            }
            if (encoded.equals(ids)) return new Wire(enumeration.generatedName(), "WireCodecs.enumIds(" + enumeration.generatedName() + ".values(), new int[]{" + String.join(", ", enumeration.constants().stream().map(ids::get).toList()) + "}, " + inner.codec() + ")", "enumSwitch:" + source.name() + ":" + method.getNameAsString() + ":" + inner.shape());
        }
        throw new Unsupported("Cannot prove matching enum ID writer");
    }
    private static SwitchExpr returnedSwitch(MethodDeclaration method) {
        var body = method.getBody().orElseThrow(() -> new Unsupported("Method has no body"));
        if (body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof SwitchExpr expression)) throw new Unsupported("Method is not a direct switch expression");
        return expression;
    }
    private Wire readerFunction(Expression expression, SourceIndex.Source source) {
        if (expression instanceof MethodReferenceExpr reference && reference.getIdentifier().equals("new")) return modelWire(model(index.resolve(reference.getScope().toString(), source)));
        if (expression instanceof MethodReferenceExpr reference && reference.getScope().toString().equals("FriendlyByteBuf") && reference.getIdentifier().startsWith("read")) return read(new MethodCallExpr(new NameExpr("input"), reference.getIdentifier()), "input", source);
        return codec(expression, source);
    }
    private Wire writerFunction(Expression expression, SourceIndex.Source source) {
        if (expression instanceof MethodReferenceExpr reference && reference.getIdentifier().equals("write") && !reference.getScope().toString().equals("FriendlyByteBuf")) return modelWire(model(index.resolve(reference.getScope().toString(), source)));
        if (expression instanceof MethodReferenceExpr reference && reference.getScope().toString().equals("FriendlyByteBuf") && reference.getIdentifier().startsWith("write")) {
            String operation = reference.getIdentifier().substring(5);
            if (SCALARS.containsKey(operation)) return scalar(operation);
            if (operation.equals("Utf")) return string("32767");
        }
        return codec(expression, source);
    }
    private Wire codec(Expression expression, SourceIndex.Source source) {
        if (expression instanceof FieldAccessExpr access) {
            String owner = index.resolve(access.getScope().toString(), source);
            String field = access.getNameAsString();
            if (owner.equals("net.minecraft.network.codec.ByteBufCodecs")) {
                if (CODEC_SCALARS.containsKey(field)) return scalar(CODEC_SCALARS.get(field));
                if (field.equals("OPTIONAL_VAR_INT")) return new Wire("java.util.OptionalInt", "WireCodecs.OPTIONAL_VAR_INT", "OPTIONAL_VAR_INT");
                if (field.equals("ROTATION_BYTE")) return new Wire("float", "VectorCodecs.ROTATION_BYTE", "ROTATION_BYTE");
                if (field.equals("VECTOR3F")) return new Wire("org.stackanvil.packetforge.commons.FloatVector3", "VectorCodecs.FLOAT", "VECTOR3F");
                if (field.equals("QUATERNIONF")) return new Wire("org.stackanvil.packetforge.commons.Quaternion", "VectorCodecs.QUATERNION", "QUATERNIONF");
                if (field.equals("BIT_SET")) return new Wire("java.util.BitSet", "WireCodecs.BIT_SET", "BIT_SET");
                if (field.equals("STRING_UTF8")) return string("32767");
                if (field.equals("BYTE_ARRAY")) return new Wire("byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
            }
            if (owner.equals("net.minecraft.resources.Identifier") && field.equals("STREAM_CODEC")) return scalar("Identifier");
            if (owner.equals("net.minecraft.core.UUIDUtil") && field.equals("STREAM_CODEC")) return scalar("UUID");
            if (owner.equals("net.minecraft.core.BlockPos") && field.equals("STREAM_CODEC")) return scalar("BlockPos");
            if (owner.equals("net.minecraft.world.phys.Vec3") && Set.of("STREAM_CODEC", "LP_STREAM_CODEC").contains(field)) return new Wire("org.stackanvil.packetforge.commons.Vector3", "VectorCodecs." + (field.equals("STREAM_CODEC") ? "DOUBLE" : "LOW_PRECISION"), field.equals("STREAM_CODEC") ? "VECTOR3" : "LP_VECTOR3");
            if (owner.equals("net.minecraft.world.level.ChunkPos") && field.equals("STREAM_CODEC")) return scalar("ChunkPos");
            if (owner.equals("net.minecraft.resources.ResourceKey") && field.equals("REGISTRY_STREAM_CODEC")) return scalar("Identifier");
            var target = index.source(owner);
            if (field.equals("STREAM_CODEC") && !(target.declaration() instanceof EnumDeclaration)) {
                Model nested = model(owner);
                return new Wire(nested.generatedName(), nested.generatedName() + ".CODEC", "model:" + owner);
            }
            Expression initializer = target.declaration().getFieldByName(field).orElseThrow(() -> new Unsupported("Missing codec field: " + expression)).getVariable(0).getInitializer().orElseThrow();
            String key = owner + "." + field;
            if (!active.add(key)) throw new Unsupported("Recursive codec alias: " + key);
            try { return codec(initializer, target); } finally { active.remove(key); }
        }
        if (expression instanceof NameExpr name) return codec(new FieldAccessExpr(new NameExpr(source.declaration().getNameAsString()), name.getNameAsString()), source);
        if (expression instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String owner = call.getScope().get().toString();
            String method = call.getNameAsString();
            if (owner.equals("ByteBufCodecs")) {
                if (method.equals("idMapper") && call.getArguments().size() == 1) return scalar("VarInt");
                if (method.equals("idMapper") && call.getArguments().size() == 2) return source.declaration() instanceof EnumDeclaration ? enumIdMapper(call, source) : scalar("VarInt");
                if (method.equals("registry") && call.getArguments().size() == 1) return scalar("VarInt");
                if (method.equals("stringUtf8") && call.getArguments().size() == 1) return string(integer(call.getArgument(0), source));
                if (method.equals("byteArray") && call.getArguments().size() == 1) return new Wire("byte[]", "WireCodecs.byteArray(" + integer(call.getArgument(0), source) + ")", "byteArray");
                if (method.equals("optional") && call.getArguments().size() == 1) return container("optional", codec(call.getArgument(0), source), "Integer.MAX_VALUE");
                if (method.equals("map") && (call.getArguments().size() == 3 || call.getArguments().size() == 4)) {
                    Wire key = codec(call.getArgument(1), source), value = codec(call.getArgument(2), source);
                    return new Wire("java.util.Map<" + boxed(key.type()) + ", " + boxed(value.type()) + ">", "WireCodecs.map(" + key.codec() + ", " + value.codec() + ", " + (call.getArguments().size() == 4 ? integer(call.getArgument(3), source) : "Integer.MAX_VALUE") + ")", "map<" + key.shape() + "," + value.shape() + ">");
                }
                if (method.equals("either") && call.getArguments().size() == 2) {
                    Wire left = codec(call.getArgument(0), source), right = codec(call.getArgument(1), source);
                    return new Wire("org.stackanvil.packetforge.commons.Either<" + boxed(left.type()) + ", " + boxed(right.type()) + ">", "WireCodecs.either(" + left.codec() + ", " + right.codec() + ")", "either<" + left.shape() + "," + right.shape() + ">");
                }
            }
            if (owner.equals("ResourceKey") && method.equals("streamCodec") && call.getArguments().size() == 1) return scalar("Identifier");
            if (method.equals("map") && call.getArguments().size() == 2) {
                String decode = call.getArgument(0).toString(), encode = call.getArgument(1).toString();
                Wire inner = codec(call.getScope().get(), source);
                if (decode.equals("Instant::ofEpochMilli") && encode.equals("Instant::toEpochMilli") && inner.shape().equals("LONG")) return new Wire("java.time.Instant", "WireCodecs.INSTANT", "INSTANT");
                if (decode.equals("Short::intValue") && encode.equals("Integer::shortValue") && inner.shape().equals("SHORT")) return new Wire("int", "WireCodecs.mapped(WireCodecs.SHORT, Short::intValue, Integer::shortValue)", "SHORT_AS_INT");
                if (decode.equals("ResourceKey::createRegistryKey") && encode.equals("ResourceKey::identifier") && inner.shape().equals("IDENTIFIER")) return inner;
            }
            if (method.equals("apply") && call.getArguments().size() == 1) {
                Wire inner = codec(call.getScope().get(), source);
                Expression argument = call.getArgument(0);
                if (argument instanceof MethodCallExpr list && list.getScope().map(Object::toString).orElse("").equals("ByteBufCodecs") && list.getNameAsString().equals("list") && list.getArguments().size() <= 1) return container("list", inner, list.getArguments().isEmpty() ? "Integer.MAX_VALUE" : integer(list.getArgument(0), source));
                if (argument instanceof MethodCallExpr collection && collection.getScope().map(Object::toString).orElse("").equals("ByteBufCodecs")) {
                    if (collection.getNameAsString().equals("fixedSizeList") && collection.getArguments().size() == 1) {
                        String size = integer(collection.getArgument(0), source);
                        return new Wire("java.util.List<" + boxed(inner.type()) + ">", "WireCodecs.fixedList(" + inner.codec() + ", " + size + ")", "fixedList(" + size + ")<" + inner.shape() + ">");
                    }
                    if (collection.getNameAsString().equals("collection") && collection.getArguments().size() == 1 && collection.getArgument(0) instanceof MethodReferenceExpr factory) return container(factory.getScope().toString().contains("Set") ? "set" : "list", inner, "Integer.MAX_VALUE");
                }
                if (argument instanceof MethodReferenceExpr ref && ref.getScope().toString().equals("ByteBufCodecs") && ref.getIdentifier().equals("optional")) return container("optional", inner, "Integer.MAX_VALUE");
            }
        }
        throw new Unsupported("Unsupported stream codec: " + shortText(expression));
    }
    private String integer(Expression expression, SourceIndex.Source source) {
        if (expression instanceof IntegerLiteralExpr literal) return Integer.toString(literal.asNumber().intValue());
        if (expression instanceof NameExpr name) {
            Expression initializer = source.declaration().getFieldByName(name.getNameAsString()).orElseThrow(() -> new Unsupported("Unresolved integer constant: " + name)).getVariable(0).getInitializer().orElseThrow();
            return integer(initializer, source);
        }
        if (expression instanceof FieldAccessExpr access) {
            if (access.toString().equals("Integer.MAX_VALUE")) return "Integer.MAX_VALUE";
            var owner = index.source(index.resolve(access.getScope().toString(), source));
            return integer(owner.declaration().getFieldByName(access.getNameAsString()).orElseThrow(() -> new Unsupported("Unresolved integer constant: " + expression)).getVariable(0).getInitializer().orElseThrow(), owner);
        }
        throw new Unsupported("Non-literal codec limit: " + expression);
    }
    private static Wire scalar(String operation) { String[] spec = SCALARS.get(operation).split(":", 2); return new Wire(spec[1], "WireCodecs." + spec[0], spec[0]); }
    private static Wire string(String max) { return new Wire("String", "WireCodecs.string(" + max + ")", "UTF8"); }
    private static Wire container(String kind, Wire inner, String max) {
        String type = switch (kind) { case "optional" -> "java.util.Optional<" + boxed(inner.type()) + ">"; case "list" -> "java.util.List<" + boxed(inner.type()) + ">"; case "set" -> "java.util.Set<" + boxed(inner.type()) + ">"; case "nullable" -> boxed(inner.type()); default -> throw new Unsupported("Unknown container: " + kind); };
        String codec = "WireCodecs." + kind + "(" + inner.codec() + (Set.of("list", "set").contains(kind) ? ", " + max : "") + ")";
        return new Wire(type, codec, kind + "<" + inner.shape() + ">");
    }
    static String boxed(String type) { return switch (type) { case "boolean" -> "Boolean"; case "byte" -> "Byte"; case "short" -> "Short"; case "int" -> "Integer"; case "long" -> "Long"; case "float" -> "Float"; case "double" -> "Double"; default -> type; }; }
    private static Wire modelWire(Model model) { return new Wire(model.generatedName(), model.generatedName() + ".CODEC", "model:" + model.originalName()); }
    private String getterField(Expression expression, SourceIndex.Source source) {
        if (expression instanceof MethodReferenceExpr getter && index.resolve(getter.getScope().toString(), source).equals(source.name())) {
            String name = getter.getIdentifier();
            if (source.declaration() instanceof RecordDeclaration record && record.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return name;
            var methods = source.declaration().getMethodsByName(name);
            if (methods.size() == 1 && methods.getFirst().getBody().isEmpty() && source.declaration() instanceof ClassOrInterfaceDeclaration clazz && clazz.isInterface()) return name;
            if (methods.size() == 1 && methods.getFirst().getParameters().isEmpty()) {
                var body = methods.getFirst().getBody().orElseThrow();
                if (body.getStatements().size() == 1 && body.getStatement(0) instanceof ReturnStmt returned) return directField(returned.getExpression().orElseThrow());
            }
        }
        if (expression instanceof LambdaExpr lambda && lambda.getParameters().size() == 1 && lambda.getBody() instanceof ExpressionStmt body && body.getExpression() instanceof FieldAccessExpr field && field.getScope().toString().equals(lambda.getParameter(0).getNameAsString())) return field.getNameAsString();
        throw new Unsupported("Composite getter is not a direct field accessor: " + shortText(expression));
    }
    private static String directField(Expression expression) {
        if (expression instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr) return field.getNameAsString();
        throw new Unsupported("Expression is not a direct field: " + shortText(expression));
    }
    private static String declaredType(String name, SourceIndex.Source source) {
        if (source.declaration() instanceof RecordDeclaration record) return record.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst().orElseThrow(() -> new Unsupported("Unknown record field: " + name)).getTypeAsString();
        if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz && clazz.isInterface()) {
            var methods = clazz.getMethodsByName(name);
            if (methods.size() == 1 && methods.getFirst().getParameters().isEmpty()) return methods.getFirst().getTypeAsString();
        }
        return source.declaration().getFieldByName(name).orElseThrow(() -> new Unsupported("Unknown field: " + name)).getVariables().stream().filter(v -> v.getNameAsString().equals(name)).findFirst().orElseThrow().getTypeAsString();
    }
    private static boolean isBuffer(String type) { return Set.of("FriendlyByteBuf", "RegistryFriendlyByteBuf", "ByteBuf").contains(type); }
    private static String shortText(Node node) { String text = node.toString().replaceAll("\\s+", " "); return text.length() > 180 ? text.substring(0, 177) + "..." : text; }
}
