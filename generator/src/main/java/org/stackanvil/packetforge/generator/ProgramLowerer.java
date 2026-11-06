package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import java.util.*;

/** Translates imperative serialization while retaining branches, locals, and bit operations. */
final class ProgramLowerer {
    record Lowered(List<Field> fields, Program program) {}

    private final Analyzer analyzer;
    private final SourceIndex.Source source;
    private final Map<String, Wire> fields = new LinkedHashMap<>();
    private final Set<String> reads = new TreeSet<>(), writes = new TreeSet<>();
    private final Map<String, String> originalTypes = new LinkedHashMap<>();
    private final Map<String, String> helperCode = new LinkedHashMap<>();
    private final Set<String> boundedArrayCounts = new HashSet<>();
    private final Map<String, String> localTypes = new HashMap<>();
    private String valueName = "value";
    private final String generatedName;

    ProgramLowerer(Analyzer analyzer, SourceIndex.Source source) {
        this(analyzer, source, analyzer.index.generated(source.name()));
    }

    ProgramLowerer(Analyzer analyzer, SourceIndex.Source source, String generatedName) {
        this.analyzer = analyzer;
        this.source = source;
        this.generatedName = generatedName;
    }

    static boolean accepts(Expression codec) {
        return codec instanceof ObjectCreationExpr anonymous
                        && anonymous.getAnonymousClassBody().isPresent()
                || codec instanceof MethodCallExpr call
                        && Set.of("codec", "ofMember", "of").contains(call.getNameAsString())
                        && call.getArguments().size() == 2
                        && call.getArgument(0) instanceof MethodReferenceExpr
                        && call.getArgument(1) instanceof MethodReferenceExpr;
    }

    Lowered lower(Expression codec) {
        BlockStmt decode, encode;
        String input, output;
        boolean constructorMode;
        if (codec instanceof ObjectCreationExpr anonymous
                && anonymous.getAnonymousClassBody().isPresent()) {
            var members = anonymous.getAnonymousClassBody().get();
            var readers = members.stream()
                    .filter(m -> m instanceof MethodDeclaration method
                            && method.getNameAsString().equals("decode"))
                    .map(m -> (MethodDeclaration) m)
                    .toList();
            var writers = members.stream()
                    .filter(m -> m instanceof MethodDeclaration method
                            && method.getNameAsString().equals("encode"))
                    .map(m -> (MethodDeclaration) m)
                    .toList();
            if (readers.size() != 1 || writers.size() != 1) throw new Unsupported("Ambiguous anonymous stream codec");
            var reader = readers.getFirst();
            var writer = writers.getFirst();
            decode = reader.getBody()
                    .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                    .clone();
            encode = writer.getBody()
                    .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                    .clone();
            input = reader.getParameter(0).getNameAsString();
            output = writer.getParameter(0).getNameAsString();
            valueName = writer.getParameter(1).getNameAsString();
            constructorMode = false;
        } else if (codec instanceof MethodCallExpr call
                && Set.of("codec", "ofMember", "of").contains(call.getNameAsString())
                && call.getArguments().size() == 2
                && call.getArgument(0) instanceof MethodReferenceExpr writer
                && call.getArgument(1) instanceof MethodReferenceExpr reader) {
            var writers = source.declaration().getMethodsByName(writer.getIdentifier()).stream()
                    .filter(m ->
                            (m.getParameters().size() == 1 || m.getParameters().size() == 2)
                                    && buffer(m.getParameter(0).getTypeAsString()))
                    .toList();
            if (writers.size() != 1) throw new Unsupported("Ambiguous imperative writer");
            var write = writers.getFirst();
            output = write.getParameter(0).getNameAsString();
            encode = write.getBody()
                    .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                    .clone();
            if (write.getParameters().size() == 2)
                valueName = write.getParameter(1).getNameAsString();
            constructorMode = reader.getIdentifier().equals("new");
            if (constructorMode) {
                var constructors = source.declaration().getMembers().stream()
                        .filter(m -> m instanceof ConstructorDeclaration c
                                && c.getParameters().size() == 1
                                && buffer(c.getParameter(0).getTypeAsString()))
                        .map(m -> (ConstructorDeclaration) m)
                        .toList();
                if (constructors.size() != 1) throw new Unsupported("Ambiguous imperative constructor");
                var constructor = constructors.getFirst();
                input = constructor.getParameter(0).getNameAsString();
                decode = constructor.getBody().clone();
            } else {
                var readers = source.declaration().getMethodsByName(reader.getIdentifier()).stream()
                        .filter(m -> m.getParameters().size() == 1
                                && buffer(m.getParameter(0).getTypeAsString()))
                        .toList();
                if (readers.size() != 1) throw new Unsupported("Ambiguous imperative reader");
                var read = readers.getFirst();
                input = read.getParameter(0).getNameAsString();
                decode = read.getBody()
                        .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                        .clone();
            }
        } else throw new Unsupported("Not an imperative stream codec");
        return lowerBodies(decode, encode, input, output, constructorMode);
    }

    Lowered lowerSerializer(MethodDeclaration reader, MethodDeclaration writer) {
        valueName = writer.getParameter(0).getNameAsString();
        return lowerBodies(
                reader.getBody().orElseThrow(() -> new Unsupported("Missing serializer reader body")),
                writer.getBody().orElseThrow(() -> new Unsupported("Missing serializer writer body")),
                reader.getParameter(0).getNameAsString(),
                writer.getParameter(1).getNameAsString(),
                false);
    }

    Lowered lowerBodies(BlockStmt decode, BlockStmt encode, String input, String output, boolean constructorMode) {
        decode = decode.clone();
        encode = encode.clone();
        if (!decode.findAll(ExplicitConstructorInvocationStmt.class).isEmpty()) {
            if (decode.getStatements().size() != 1
                    || !(decode.getStatement(0) instanceof ExplicitConstructorInvocationStmt invocation)
                    || !invocation.isThis())
                throw new Unsupported("Imperative constructor delegates with extra statements");
            decode = new BlockStmt()
                    .addStatement(new ReturnStmt(new ObjectCreationExpr(
                            null,
                            StaticJavaParser.parseClassOrInterfaceType(source.name()),
                            new NodeList<>(invocation.getArguments().stream()
                                    .map(Expression::clone)
                                    .toList()))));
            constructorMode = false;
        }
        for (var loop : decode.findAll(ForStmt.class)) {
            if (!(loop.getCompare().orElse(null) instanceof BinaryExpr compare)
                    || compare.getOperator() != BinaryExpr.Operator.LESS
                    || !(compare.getRight() instanceof NameExpr count)
                    || !(loop.getBody() instanceof BlockStmt body)
                    || body.getStatements().isEmpty()
                    || !body.findAll(IfStmt.class).isEmpty()
                    || !body.findAll(BreakStmt.class).isEmpty()
                    || !body.findAll(ContinueStmt.class).isEmpty()
                    || !body.findAll(ReturnStmt.class).isEmpty()) continue;
            if (!(compare.getLeft() instanceof NameExpr induction)
                    || loop.getInitialization().size() != 1
                    || !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr initial)
                    || initial.getVariables().size() != 1
                    || !initial.getVariable(0).getNameAsString().equals(induction.getNameAsString())
                    || !initial.getVariable(0)
                            .getInitializer()
                            .map(Object::toString)
                            .orElse("")
                            .equals("0")
                    || loop.getUpdate().size() != 1
                    || !Set.of(induction + "++", "++" + induction)
                            .contains(loop.getUpdate().get(0).toString())
                    || body.findAll(AssignExpr.class).stream()
                            .anyMatch(a -> a.getTarget() instanceof NameExpr n
                                    && Set.of(induction.getNameAsString(), count.getNameAsString())
                                            .contains(n.getNameAsString()))) continue;
            if (!(body.getStatement(0) instanceof ExpressionStmt expression)
                    || !(expression.getExpression() instanceof VariableDeclarationExpr values)
                    || values.getVariables().size() != 1
                    || !(values.getVariable(0).getInitializer().orElse(null) instanceof MethodCallExpr read)
                    || !read.getScope().map(Object::toString).orElse("").equals(input)
                    || !Set.of("readVarLong", "readVarInt", "readByte", "readShort", "readInt", "readLong")
                            .contains(read.getNameAsString())) continue;
            boundedArrayCounts.add(count.getNameAsString());
        }
        collectTypes(source, new HashSet<>());
        for (var variable : decode.findAll(VariableDeclarator.class))
            localTypes.put(variable.getNameAsString(), variable.getTypeAsString());
        for (var variable : encode.findAll(VariableDeclarator.class))
            localTypes.put(variable.getNameAsString(), variable.getTypeAsString());
        Set<String> used = new HashSet<>();
        for (var access : decode.findAll(FieldAccessExpr.class))
            if (access.getScope() instanceof ThisExpr) used.add(access.getNameAsString());
        for (var access : encode.findAll(FieldAccessExpr.class))
            if (access.getScope() instanceof ThisExpr
                    || access.getScope().toString().equals(valueName)) used.add(access.getNameAsString());
        for (var call : encode.findAll(MethodCallExpr.class))
            if (call.getScope().map(Object::toString).orElse("").equals(valueName)
                    && originalTypes.containsKey(call.getNameAsString())) used.add(call.getNameAsString());
        for (var assignment : decode.findAll(AssignExpr.class))
            if (assignment.getTarget() instanceof FieldAccessExpr target && target.getScope() instanceof ThisExpr) {
                try {
                    fields.put(target.getNameAsString(), analyzer.read(assignment.getValue(), input, source));
                } catch (Unsupported ignored) {
                }
            }
        Map<String, Wire> inferred = new HashMap<>(fields);
        fields.clear();
        for (var entry : originalTypes.entrySet())
            if (used.contains(entry.getKey()))
                fields.put(
                        entry.getKey(),
                        inferred.containsKey(entry.getKey())
                                ? inferred.get(entry.getKey())
                                : valueType(entry.getValue()));
        if (!fields.keySet().containsAll(used)) throw new Unsupported("Imperative layout uses unknown fields");
        decode.accept(new Translator(input, false), null);
        encode.accept(new Translator(output, true), null);
        String sourceInput = input, sourceOutput = output;
        if (!input.equals("input"))
            for (var name : decode.findAll(NameExpr.class))
                if (name.getNameAsString().equals(sourceInput)) name.setName("input");
        if (!output.equals("output"))
            for (var name : encode.findAll(NameExpr.class))
                if (name.getNameAsString().equals(sourceOutput)) name.setName("output");
        if (!reads.equals(writes) || reads.isEmpty())
            throw new Unsupported("Imperative reader/writer wire operations differ: " + reads + " / " + writes);
        List<Field> layout = fields.entrySet().stream()
                .map(entry -> new Field(
                        entry.getKey(), originalTypes.get(entry.getKey()), entry.getValue(), entry.getValue()))
                .toList();
        StringBuilder body = new StringBuilder();
        if (constructorMode)
            for (var field : layout)
                body.append(field.read().type())
                        .append(" ")
                        .append(local(field.name()))
                        .append(" = ")
                        .append(defaultValue(field.read().type()))
                        .append(";\n");
        body.append(contents(decode));
        if (constructorMode)
            body.append("\nreturn new ")
                    .append(simple(generatedName))
                    .append("(")
                    .append(String.join(
                            ", ",
                            fields.keySet().stream().map(ProgramLowerer::local).toList()))
                    .append(");");
        return new Lowered(
                layout,
                new Program(body.toString(), contents(encode), List.copyOf(reads), List.copyOf(helperCode.values())));
    }

    private void collectTypes(SourceIndex.Source target, Set<String> visited) {
        if (!visited.add(target.name())) return;
        if (target.declaration() instanceof ClassOrInterfaceDeclaration clazz)
            for (var parent : clazz.getExtendedTypes())
                collectTypes(analyzer.index.source(analyzer.index.resolve(parent.getNameWithScope(), target)), visited);
        if (target.declaration() instanceof RecordDeclaration record)
            for (var parameter : record.getParameters())
                originalTypes.put(parameter.getNameAsString(), parameter.getTypeAsString());
        for (var declaration : target.declaration().getFields())
            if (!declaration.isStatic())
                for (var variable : declaration.getVariables())
                    originalTypes.put(variable.getNameAsString(), variable.getTypeAsString());
    }

    private Map<String, Expression> constructedFields(
            SourceIndex.Source target, NodeList<Expression> arguments, Set<String> visited) {
        if (!visited.add(target.name() + "#" + arguments.size()))
            throw new Unsupported("Recursive constructor delegation");
        if (target.declaration() instanceof RecordDeclaration record
                && record.getParameters().size() == arguments.size()) {
            Map<String, Expression> result = new HashMap<>();
            for (int i = 0; i < arguments.size(); i++)
                result.put(
                        record.getParameter(i).getNameAsString(),
                        arguments.get(i).clone());
            return result;
        }
        var constructors = target.declaration().getMembers().stream()
                .filter(m -> m instanceof ConstructorDeclaration c
                        && c.getParameters().size() == arguments.size())
                .map(m -> (ConstructorDeclaration) m)
                .filter(c -> {
                    for (int i = 0; i < arguments.size(); i++) {
                        String actual = expressionType(arguments.get(i));
                        if (actual != null
                                && !simple(actual)
                                        .equals(simple(c.getParameter(i).getTypeAsString()))) return false;
                    }
                    return true;
                })
                .toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous value constructor");
        var constructor = constructors.getFirst();
        Map<String, Expression> parameters = new HashMap<>(), result = new HashMap<>();
        for (int i = 0; i < arguments.size(); i++)
            parameters.put(constructor.getParameter(i).getNameAsString(), arguments.get(i));
        for (var statement : constructor.getBody().getStatements()) {
            if (statement instanceof ExplicitConstructorInvocationStmt invocation && invocation.isThis()) {
                NodeList<Expression> values = new NodeList<>();
                invocation.getArguments().forEach(e -> values.add(substitute(e, parameters)));
                result.putAll(constructedFields(target, values, visited));
            } else if (statement instanceof ExplicitConstructorInvocationStmt invocation
                    && !invocation.isThis()
                    && target.declaration() instanceof ClassOrInterfaceDeclaration clazz
                    && clazz.getExtendedTypes().size() == 1) {
                var parent = analyzer.index.source(
                        analyzer.index.resolve(clazz.getExtendedTypes().get(0).getNameWithScope(), target));
                NodeList<Expression> values = new NodeList<>();
                invocation.getArguments().forEach(e -> values.add(substitute(e, parameters)));
                result.putAll(constructedFields(parent, values, visited));
            } else if (statement instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof AssignExpr assignment
                    && assignment.getTarget() instanceof FieldAccessExpr field
                    && field.getScope() instanceof ThisExpr
                    && assignment.getOperator() == AssignExpr.Operator.ASSIGN)
                result.put(field.getNameAsString(), substitute(assignment.getValue(), parameters));
            else throw new Unsupported("Constructor contains non-assignment behavior");
        }
        return result;
    }

    private String expressionType(Expression expression) {
        if (expression instanceof NameExpr name) return localTypes.get(name.getNameAsString());
        if (expression instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr)
            return originalTypes.get(field.getNameAsString());
        if (expression instanceof ObjectCreationExpr created) return created.getTypeAsString();
        if (expression instanceof CastExpr cast) return cast.getTypeAsString();
        if (expression instanceof IntegerLiteralExpr
                || expression instanceof BinaryExpr binary
                        && !Set.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)
                                .contains(binary.getOperator())) return "int";
        if (expression instanceof BooleanLiteralExpr) return "boolean";
        if (expression instanceof MethodCallExpr call) {
            if (call.getNameAsString().equals("get")
                    && call.getArguments().isEmpty()
                    && call.getScope().isPresent()) {
                String receiver = expressionType(call.getScope().get());
                if (receiver != null && receiver.startsWith("Optional<"))
                    return receiver.substring(9, receiver.length() - 1);
            }
            try {
                var target = helperOwner(call.getScope().map(Object::toString).orElse(""), call.getNameAsString());
                var methods = target.declaration().getMethodsByName(call.getNameAsString()).stream()
                        .filter(m ->
                                m.getParameters().size() == call.getArguments().size())
                        .toList();
                if (methods.size() == 1) return methods.getFirst().getTypeAsString();
            } catch (Unsupported ignored) {
            }
        }
        return null;
    }

    private static Expression substitute(Expression expression, Map<String, Expression> parameters) {
        return (Expression) expression
                .clone()
                .accept(
                        new ModifierVisitor<Void>() {
                            @Override
                            public Visitable visit(NameExpr name, Void ignored) {
                                return parameters.containsKey(name.getNameAsString())
                                        ? parameters.get(name.getNameAsString()).clone()
                                        : name;
                            }
                        },
                        null);
    }

    private Field optionalMapping(MethodCallExpr call) {
        if (!call.getNameAsString().equals("map")
                || call.getArguments().size() != 1
                || !(call.getArgument(0) instanceof MethodReferenceExpr reference)
                || call.getScope().isEmpty())
            throw new Unsupported("Optional projection is not a direct getter reference");
        String type = expressionType(call.getScope().get());
        if (type == null || !type.startsWith("Optional<"))
            throw new Unsupported("Optional projection receiver has no known value type");
        Wire value = valueType(type.substring(9, type.length() - 1));
        if (!value.shape().startsWith("model:"))
            throw new Unsupported("Optional projection does not reference a wire record");
        var model = analyzer.models.get(value.shape().substring(6));
        var target = analyzer.index.source(
                analyzer.index.resolve(reference.getScope().toString(), source));
        var concrete = analyzer.index.source(model.originalName().split("#", 2)[0]);
        if (!inherits(concrete, target.name(), new HashSet<>()))
            throw new Unsupported("Optional getter belongs to another value type");
        var concreteGetter = reference.clone().setScope(new TypeExpr(StaticJavaParser.parseType(concrete.name())));
        String field = analyzer.getterField(concreteGetter, concrete);
        var fields = model.fields().stream().filter(f -> f.name().equals(field)).toList();
        if (fields.size() != 1) throw new Unsupported("Optional projection does not select a wire field");
        return fields.getFirst();
    }

    private boolean inherits(SourceIndex.Source value, String parent, Set<String> visited) {
        if (value.name().equals(parent)) return true;
        if (!visited.add(value.name())) return false;
        List<com.github.javaparser.ast.type.ClassOrInterfaceType> parents = new ArrayList<>();
        if (value.declaration() instanceof RecordDeclaration record) parents.addAll(record.getImplementedTypes());
        if (value.declaration() instanceof ClassOrInterfaceDeclaration clazz) {
            parents.addAll(clazz.getImplementedTypes());
            parents.addAll(clazz.getExtendedTypes());
        }
        for (var type : parents)
            if (inherits(
                    analyzer.index.source(analyzer.index.resolve(type.getNameWithScope(), value)), parent, visited))
                return true;
        return false;
    }

    private Wire valueType(String name) {
        name = name.replaceAll("@[A-Za-z0-9_.]+(?:\\([^)]*\\))?\\s*", "").strip();
        String codec = switch (name) {
            case "boolean" -> "BOOL";
            case "byte" -> "BYTE";
            case "short" -> "SHORT";
            case "int" -> "INT";
            case "long" -> "LONG";
            case "float" -> "FLOAT";
            case "double" -> "DOUBLE";
            default -> null;
        };
        if (codec != null) return new Wire(name, "WireCodecs." + codec, codec);
        if (name.equals("String")) return new Wire("String", "WireCodecs.STRING", "UTF8");
        if (name.equals("Identifier")) return new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        if (name.equals("BlockPos"))
            return new Wire(
                    "org.stackanvil.packetforge.commons.BlockPosition", "WireCodecs.BLOCK_POSITION", "BLOCK_POSITION");
        if (name.equals("CompoundTag"))
            return new Wire(
                    "org.stackanvil.packetforge.commons.NbtTag.Compound",
                    "NbtCodecs.NULLABLE_COMPOUND",
                    "NBT_COMPOUND_NULLABLE");
        if (name.equals("Component")) return TextWire.component(false);
        if (name.equals("Tag")) return new Wire("org.stackanvil.packetforge.commons.NbtTag", "NbtCodecs.TAG", "NBT");
        if (name.equals("UUID")) return new Wire("java.util.UUID", "WireCodecs.UUID_CODEC", "UUID_CODEC");
        if (name.equals("byte[]")) return new Wire("byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
        if (name.equals("VecDelta")) return new MovementDeltaAnalyzer(analyzer).value();
        if (name.equals("BlockState")) return new Wire("int", "WireCodecs.VAR_INT", "REGISTRY_BLOCK_STATE");
        if (name.endsWith("[]")) {
            Wire component = valueType(name.substring(0, name.length() - 2));
            return new Wire(component.type() + "[]", "", "program-array<" + component.shape() + ">");
        }
        if (name.equals("ChunkPos"))
            return new Wire(
                    "org.stackanvil.packetforge.commons.ChunkPosition", "WireCodecs.CHUNK_POSITION", "CHUNK_POSITION");
        if (name.equals("Vec3i"))
            return new Wire("org.stackanvil.packetforge.commons.IntVector3", "VectorCodecs.INT", "VECTOR3I");
        if (name.equals("Vec3"))
            return new Wire("org.stackanvil.packetforge.commons.Vector3", "VectorCodecs.DOUBLE", "VECTOR3");
        var parsed = StaticJavaParser.parseType(name);
        if (parsed.isClassOrInterfaceType()
                && parsed.asClassOrInterfaceType().getTypeArguments().isPresent()) {
            String raw = parsed.asClassOrInterfaceType().getNameAsString();
            var arguments = parsed.asClassOrInterfaceType().getTypeArguments().get();
            if (Set.of("Optional", "List", "Set", "Collection", "ArrayList", "HashSet")
                            .contains(raw)
                    && arguments.size() == 1) {
                Wire inner = valueType(arguments.get(0).asString());
                String kind = raw.equals("Optional") ? "optional" : raw.contains("Set") ? "set" : "list";
                return new Wire(
                        "java.util."
                                + (kind.equals("optional") ? "Optional" : kind.equals("set") ? "Set" : "List")
                                + "<"
                                + boxed(inner.type())
                                + ">",
                        inner.codec().isEmpty()
                                ? ""
                                : "WireCodecs."
                                        + kind
                                        + "("
                                        + inner.codec()
                                        + (kind.equals("optional") ? "" : ", Integer.MAX_VALUE")
                                        + ")",
                        kind + "<" + inner.shape() + ">");
            }
            if (raw.equals("Pair") && arguments.size() == 2) {
                Wire first = valueType(arguments.get(0).asString()),
                        second = valueType(arguments.get(1).asString());
                return new Wire(
                        "org.stackanvil.packetforge.commons.Pair<"
                                + boxed(first.type())
                                + ", "
                                + boxed(second.type())
                                + ">",
                        "",
                        "program-pair<" + first.shape() + "," + second.shape() + ">");
            }
            if (raw.equals("Holder") && arguments.size() == 1)
                return valueType(arguments.get(0).asString());
            if (raw.equals("ResourceKey")) return new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        }
        var target = analyzer.index.source(analyzer.index.resolve(name, source));
        if (target.declaration() instanceof EnumDeclaration) {
            Model enumeration = analyzer.model(target.name());
            return new Wire(
                    enumeration.generatedName(),
                    "WireCodecs.enumOrdinal(" + enumeration.generatedName() + ".values())",
                    "enum:" + enumeration.originalName());
        }
        try {
            return analyzer.codec(new FieldAccessExpr(new NameExpr(target.name()), "STREAM_CODEC"), target);
        } catch (Unsupported codecMissing) {
            return modelWire(analyzer.model(target.name()));
        }
    }

    private final class Translator extends ModifierVisitor<Void> {
        final String buffer;
        final boolean encode;

        Translator(String buffer, boolean encode) {
            this.buffer = buffer;
            this.encode = encode;
        }

        @Override
        public Visitable visit(MethodCallExpr call, Void ignored) {
            MethodCallExpr original = call.clone();
            String scope = original.getScope().map(Object::toString).orElse("");
            String method = original.getNameAsString();
            if (scope.equals(buffer)) {
                if (method.equals("readBytes") && !encode && call.getArguments().size() == 1) {
                    reads.add("FIXED_BYTES");
                    return expression("WireCodecs.readFixedInto(" + buffer + ", " + call.getArgument(0) + ")");
                }
                if (method.equals("writeBytes") && encode && call.getArguments().size() == 1) {
                    writes.add("FIXED_BYTES");
                    Expression value = (Expression) call.getArgument(0).accept(this, null);
                    return expression(
                            "WireCodecs.fixedBytes((" + value + ").length).encode(" + buffer + ", " + value + ")");
                }
                if (!encode && method.startsWith("read")) {
                    Wire wire = analyzer.read(original, buffer, source);
                    reads.add(physical(wire.shape()));
                    String code = wire.codec() + ".decode(" + buffer + ")";
                    if (Set.of("boolean", "byte", "short", "int", "long", "float", "double")
                            .contains(wire.type())) code = "(" + wire.type() + ")(" + code + ")";
                    if (method.equals("readUnsignedByte")) code = "(short)(int)(" + code + ")";
                    return expression(code);
                }
                if (encode && method.startsWith("write") && !call.getArguments().isEmpty()) {
                    String suffix = method.substring(5);
                    Wire wire;
                    if (Set.of("Optional", "Nullable", "Collection").contains(suffix)
                            && original.getArgument(0) instanceof FieldAccessExpr field
                            && field.getScope() instanceof ThisExpr
                            && fields.containsKey(field.getNameAsString())) {
                        wire = analyzer.write(
                                original, buffer, field.getNameAsString(), fields.get(field.getNameAsString()), source);
                    } else if (suffix.equals("Enum")) {
                        wire = fieldWire(original.getArgument(0));
                        if (!wire.shape().startsWith("enum:"))
                            throw new Unsupported("Imperative enum write is not an enum field");
                    } else {
                        String readMethod = method.replaceFirst("write", "read");
                        MethodCallExpr read = new MethodCallExpr(new NameExpr("input"), readMethod);
                        for (int i = 1; i < original.getArguments().size(); i++)
                            read.addArgument(original.getArgument(i).clone());
                        wire = analyzer.read(read, "input", source);
                    }
                    writes.add(physical(wire.shape()));
                    call.setArgument(0, (Expression) call.getArgument(0).accept(this, null));
                    String argument = call.getArgument(0).toString();
                    if (Set.of("boolean", "byte", "short", "int", "long", "float", "double")
                            .contains(wire.type())) argument = "(" + wire.type() + ")(" + argument + ")";
                    return expression(wire.codec() + ".encode(" + buffer + ", " + argument + ")");
                }
                throw new Unsupported("Unsupported imperative buffer operation: " + original);
            }
            if (scope.equals("VecDelta")
                    && Set.of("read", "write").contains(method)
                    && original.getArguments().size() == 2
                    && original.getArgument(0).toString().equals(buffer)) {
                new MovementDeltaAnalyzer(analyzer).value();
                if (encode && method.equals("write")) writes.add("MOVEMENT_DELTA");
                else if (!encode && method.equals("read")) reads.add("MOVEMENT_DELTA");
                else throw new Unsupported("Movement-delta helper used in the wrong direction");
                Expression argument = (Expression) original.getArgument(1).accept(this, null);
                return expression("MovementCodecs." + method + "(" + buffer + ", " + argument + ")");
            }
            if (method.equals("byId")
                    && original.getArguments().size() == 1
                    && original.getScope().orElse(null) instanceof FieldAccessExpr registry
                    && registry.getNameAsString().equals("BLOCK_STATE_REGISTRY")
                    && analyzer.index
                            .resolve(registry.getScope().toString(), source)
                            .equals("net.minecraft.world.level.block.Block")) {
                reads.add("REGISTRY_BLOCK_STATE");
                return original.getArgument(0).accept(this, null);
            }
            if (method.equals("getId")
                    && original.getArguments().size() == 1
                    && analyzer.index.resolve(scope, source).equals("net.minecraft.world.level.block.Block")) {
                writes.add("REGISTRY_BLOCK_STATE");
                return original.getArgument(0).accept(this, null);
            }
            if (Set.of("VarInt", "VarLong").contains(scope)
                    && method.equals("read")
                    && original.getArguments().size() == 1
                    && original.getArgument(0).toString().equals(buffer)
                    && !encode) return new MethodCallExpr(new NameExpr(buffer), "read" + scope).accept(this, null);
            if (Set.of("VarInt", "VarLong").contains(scope)
                    && method.equals("write")
                    && original.getArguments().size() == 2
                    && original.getArgument(0).toString().equals(buffer)
                    && encode)
                return new MethodCallExpr(
                                new NameExpr(buffer),
                                "write" + scope,
                                new NodeList<>(original.getArgument(1).clone()))
                        .accept(this, null);
            if (scope.equals(valueName) && encode && call.getArguments().isEmpty() && fields.containsKey(method)) {
                call.setScope(new NameExpr("value"));
                return call;
            }
            if (method.equals("decode")
                    && original.getArguments().size() == 1
                    && original.getArgument(0).toString().equals(buffer)
                    && !encode) {
                Wire wire = analyzer.codec(
                        original.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")),
                        source);
                reads.add(physical(wire.shape()));
                return expression(wire.codec() + ".decode(" + buffer + ")");
            }
            if (method.equals("encode")
                    && original.getArguments().size() == 2
                    && original.getArgument(0).toString().equals(buffer)
                    && encode) {
                Wire wire = analyzer.codec(
                        original.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")),
                        source);
                writes.add(physical(wire.shape()));
                call.setArgument(1, (Expression) call.getArgument(1).accept(this, null));
                return expression(wire.codec() + ".encode(" + buffer + ", " + call.getArgument(1) + ")");
            }
            // Enum.ordinal is a JDK operation on generated enums.
            if (method.equals("ordinal")
                    && call.getArguments().isEmpty()
                    && original.getScope().isPresent()) {
                String type = expressionType(original.getScope().get());
                Wire wire = type == null ? fieldWire(original.getScope().get()) : valueType(type);
                if (!wire.shape().startsWith("enum:")) throw new Unsupported("Ordinal receiver is not an enum");
                call.setScope((Expression) call.getScope().orElseThrow().accept(this, null));
                return call;
            }
            if (scope.equals("Lists")
                    && method.equals("newArrayList")
                    && original.getArguments().isEmpty()) return expression("new java.util.ArrayList<>()");
            if (scope.equals("Pair")
                    && method.equals("of")
                    && original.getArguments().size() == 2) {
                Expression a = (Expression) original.getArgument(0).accept(this, null),
                        b = (Expression) original.getArgument(1).accept(this, null);
                return expression("new org.stackanvil.packetforge.commons.Pair<>(" + a + ", " + b + ")");
            }
            if (method.equals("get")
                    && original.getArguments().size() == 1
                    && original.getScope().orElse(null) instanceof FieldAccessExpr values
                    && values.getNameAsString().equals("VALUES")) {
                var owner = analyzer.index.source(
                        analyzer.index.resolve(values.getScope().toString(), source));
                if (!(owner.declaration() instanceof EnumDeclaration))
                    throw new Unsupported("Indexed values are not an enum");
                var initializer = owner.declaration()
                        .getFieldByName("VALUES")
                        .flatMap(f -> f.getVariable(0).getInitializer())
                        .orElseThrow(() -> new Unsupported("Enum has no values list"));
                if (!initializer.toString().equals("List.of(values())"))
                    throw new Unsupported("Enum values list changes its order");
                return expression(analyzer.model(owner.name()).generatedName()
                        + ".values()["
                        + original.getArgument(0).accept(this, null)
                        + "]");
            }
            if (method.equals("map")
                    && call.getArguments().size() == 1
                    && original.getArgument(0) instanceof MethodReferenceExpr) {
                Field projected = optionalMapping(original);
                call.setScope((Expression) original.getScope().orElseThrow().accept(this, null));
                call.setArgument(
                        0,
                        expression(
                                "$packetforge_mapped_value -> $packetforge_mapped_value." + projected.name() + "()"));
                return call;
            }
            if (method.equals("ifPresent")
                    && call.getArguments().size() == 1
                    && original.getArgument(0) instanceof MethodReferenceExpr reference
                    && reference.getScope().toString().equals(buffer)
                    && reference.getIdentifier().startsWith("write")
                    && encode
                    && original.getScope().orElse(null) instanceof MethodCallExpr mapped) {
                Field projected = optionalMapping(mapped);
                Wire wire = analyzer.read(
                        new MethodCallExpr(
                                new NameExpr("input"), reference.getIdentifier().replaceFirst("write", "read")),
                        "input",
                        source);
                if (!wire.type().equals(projected.read().type()))
                    throw new Unsupported("Optional projection writer changes its value type");
                writes.add(physical(wire.shape()));
                call.setScope((Expression) original.getScope().get().accept(this, null));
                call.setArgument(
                        0,
                        expression("$packetforge_optional_value -> "
                                + wire.codec()
                                + ".encode("
                                + buffer
                                + ", $packetforge_optional_value)"));
                return call;
            }
            if (Set.of("Optional", "List", "Set", "Map").contains(scope)
                    && Set.of("of", "empty", "ofNullable", "copyOf").contains(method)) {
                call.setScope(new NameExpr("java.util." + scope));
                for (int i = 0; i < call.getArguments().size(); i++)
                    call.setArgument(i, (Expression) call.getArgument(i).accept(this, null));
                return call;
            }
            if (scope.equals("Mth") && Set.of("clamp", "abs", "min", "max").contains(method)) {
                call.setScope(new NameExpr("Math"));
                for (int i = 0; i < call.getArguments().size(); i++)
                    call.setArgument(i, (Expression) call.getArgument(i).accept(this, null));
                return call;
            }
            String scopeType = expressionType(original.getScope().orElse(new NameExpr("$unknown")));
            if (scopeType == null
                    && original.getScope().orElse(null) instanceof FieldAccessExpr access
                    && access.getScope() instanceof ThisExpr) scopeType = originalTypes.get(access.getNameAsString());
            if (scopeType != null
                    && scopeType.equals("VecDelta")
                    && method.equals("stepCount")
                    && original.getArguments().isEmpty()) {
                call.setScope((Expression) original.getScope().orElseThrow().accept(this, null));
                return call;
            }
            if (scopeType != null
                    && scopeType.startsWith("Pair<")
                    && Set.of("getFirst", "getSecond").contains(method)
                    && original.getArguments().isEmpty()) {
                call.setScope((Expression) call.getScope().orElseThrow().accept(this, null));
                call.setName(method.equals("getFirst") ? "first" : "second");
                return call;
            }
            if (scopeType != null
                    && Set.of(
                                    "isPresent",
                                    "isEmpty",
                                    "get",
                                    "orElse",
                                    "orElseThrow",
                                    "size",
                                    "add",
                                    "put",
                                    "contains",
                                    "iterator")
                            .contains(method)
                    && scopeType.matches("(?:Optional|List|Set|Map|ArrayList|HashSet|Collection)<.*")) {
                call.setScope((Expression) call.getScope().orElseThrow().accept(this, null));
                for (int i = 0; i < call.getArguments().size(); i++)
                    call.setArgument(i, (Expression) call.getArgument(i).accept(this, null));
                return call;
            }
            if (Set.of("getX", "getY", "getZ").contains(method)
                    && original.getArguments().isEmpty()
                    && Set.of("BlockPos", "Vec3i").contains(scopeType == null ? "" : scopeType)) {
                call.setScope((Expression) call.getScope().orElseThrow().accept(this, null));
                call.setName(method.substring(3).toLowerCase(java.util.Locale.ROOT));
                return call;
            }
            if (Set.of("x", "y", "z").contains(method)
                    && original.getArguments().isEmpty()
                    && scopeType != null
                    && Set.of("Vec3", "ChunkPos").contains(scopeType)) {
                call.setScope((Expression) call.getScope().orElseThrow().accept(this, null));
                return call;
            }
            SourceIndex.Source target = helperOwner(scope, method);
            var candidates = target.declaration().getMethodsByName(method).stream()
                    .filter(m -> m.isStatic()
                            && m.getParameters().size()
                                    == original.getArguments().size())
                    .toList();
            if (candidates.size() != 1) throw new Unsupported("Ambiguous imperative helper: " + original);
            var helper = candidates.getFirst();
            if (!encode
                    && helper.getParameters().size() == 1
                    && buffer(helper.getParameter(0).getTypeAsString())
                    && original.getArgument(0).toString().equals(buffer)
                    && analyzer.index.resolve(helper.getTypeAsString(), target).equals(target.name())) {
                Wire wire = modelWire(analyzer.model(target.name()));
                reads.add(physical(wire.shape()));
                return expression(wire.codec() + ".decode(" + buffer + ")");
            }
            if (encode
                    && helper.getParameters().size() == 2
                    && buffer(helper.getParameter(0).getTypeAsString())
                    && original.getArgument(0).toString().equals(buffer)
                    && analyzer.index
                            .resolve(helper.getParameter(1).getTypeAsString(), target)
                            .equals(target.name())) {
                Wire wire = modelWire(analyzer.model(target.name()));
                writes.add(physical(wire.shape()));
                Expression value = (Expression) original.getArgument(1).accept(this, null);
                return expression(wire.codec() + ".encode(" + buffer + ", " + value + ")");
            }
            String key = target.name() + ":" + method;
            String generated = "$helper_" + Integer.toUnsignedString(key.hashCode()) + "_" + method;
            if (!helperCode.containsKey(key)) {
                if ((!helper.getType().isPrimitiveType() && !helper.getType().isVoidType())
                        || helper.getParameters().stream()
                                .anyMatch(p -> !p.getType().isPrimitiveType() && !buffer(p.getTypeAsString())))
                    throw new Unsupported("Imperative helper has domain object parameters");
                helperCode.put(key, "");
                MethodDeclaration lowered = helper.clone();
                lowered.setName(generated).setPublic(false).setPrivate(true).setProtected(false);
                String helperBuffer = "$noBuffer";
                for (var parameter : lowered.getParameters())
                    if (buffer(parameter.getTypeAsString())) {
                        helperBuffer = parameter.getNameAsString();
                        parameter.setType("ByteBuf");
                    }
                for (var variable : lowered.findAll(VariableDeclarator.class))
                    localTypes.put(variable.getNameAsString(), variable.getTypeAsString());
                lowered.getBody()
                        .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                        .accept(new Translator(helperBuffer, encode), null);
                helperCode.put(key, lowered.toString());
            }
            NodeList<Expression> values = new NodeList<>();
            for (Expression argument : original.getArguments()) values.add((Expression) argument.accept(this, null));
            return new MethodCallExpr(null, generated, values);
        }

        @Override
        public Visitable visit(FieldAccessExpr field, Void ignored) {
            FieldAccessExpr original = field.clone();
            if (Set.of("Integer", "Long", "Float", "Double")
                            .contains(original.getScope().toString())
                    && Set.of("MIN_VALUE", "MAX_VALUE").contains(original.getNameAsString())) return field;
            if (original.getNameAsString().equals("length")) {
                field.setScope((Expression) field.getScope().accept(this, null));
                return field;
            }
            if (original.getScope() instanceof ThisExpr
                    || original.getScope().toString().equals(valueName)) {
                if (!fields.containsKey(original.getNameAsString()))
                    throw new Unsupported("Unknown imperative field: " + original);
                return encode
                        ? expression("value." + original.getNameAsString() + "()")
                        : new NameExpr(local(original.getNameAsString()));
            }
            try {
                String owner = analyzer.index.resolve(original.getScope().toString(), source);
                var target = analyzer.index.source(owner);
                if (target.declaration() instanceof EnumDeclaration enumeration
                        && enumeration.getEntries().stream()
                                .anyMatch(c -> c.getNameAsString().equals(original.getNameAsString())))
                    return expression(analyzer.index.generated(owner) + "." + original.getNameAsString());
            } catch (Unsupported ignoredResolution) {
            }
            try {
                var target = analyzer.index.source(
                        analyzer.index.resolve(original.getScope().toString(), source));
                var initializer = target.declaration()
                        .getFieldByName(original.getNameAsString())
                        .flatMap(f -> f.getVariable(0).getInitializer())
                        .orElse(null);
                if (initializer != null && initializer.toString().equals("Component.empty()"))
                    return expression("net.kyori.adventure.text.Component.empty()");
                if (initializer instanceof LiteralExpr) return initializer.clone();
            } catch (Unsupported missingConstant) {
            }
            throw new Unsupported("Unknown imperative constant: " + original);
        }

        @Override
        public Visitable visit(ObjectCreationExpr creation, Void ignored) {
            if (Set.of("IllegalArgumentException", "IllegalStateException").contains(creation.getTypeAsString())) {
                super.visit(creation, ignored);
                return creation;
            }
            if (!encode
                    && analyzer.index
                            .resolve(creation.getTypeAsString(), source)
                            .equals(source.name())) {
                Map<String, Expression> values = constructedFields(source, creation.getArguments(), new HashSet<>());
                NodeList<Expression> arguments = new NodeList<>();
                for (String field : fields.keySet()) {
                    Expression value = values.get(field);
                    if (value == null) throw new Unsupported("Constructor does not initialize " + field);
                    arguments.add((Expression) value.accept(this, null));
                }
                return new ObjectCreationExpr(
                        null, StaticJavaParser.parseClassOrInterfaceType(generatedName), arguments);
            }
            String owner = analyzer.index.resolve(creation.getType().getNameWithScope(), source);
            if (Set.of("net.minecraft.core.BlockPos", "net.minecraft.core.Vec3i")
                                    .contains(owner)
                            && creation.getArguments().size() == 3
                    || owner.equals("net.minecraft.world.phys.Vec3")
                            && creation.getArguments().size() == 3
                    || owner.equals("net.minecraft.world.level.ChunkPos")
                            && creation.getArguments().size() == 2) {
                super.visit(creation, ignored);
                creation.setType(
                        owner.endsWith("ChunkPos")
                                ? "org.stackanvil.packetforge.commons.ChunkPosition"
                                : owner.endsWith("BlockPos")
                                        ? "org.stackanvil.packetforge.commons.BlockPosition"
                                        : owner.endsWith("Vec3i")
                                                ? "org.stackanvil.packetforge.commons.IntVector3"
                                                : "org.stackanvil.packetforge.commons.Vector3");
                return creation;
            }
            try {
                Wire targetWire = analyzer.codec(new FieldAccessExpr(new NameExpr(owner), "STREAM_CODEC"), source);
                if (targetWire.shape().startsWith("model:")) {
                    var model = analyzer.models.get(targetWire.shape().substring(6));
                    var target = analyzer.index.source(owner);
                    Map<String, Expression> values =
                            constructedFields(target, creation.getArguments(), new HashSet<>());
                    NodeList<Expression> arguments = new NodeList<>();
                    for (var field : model.fields()) {
                        if (!values.containsKey(field.name()))
                            throw new Unsupported("Projected constructor omits wire field: " + field.name());
                        arguments.add((Expression) values.get(field.name()).accept(this, null));
                    }
                    return new ObjectCreationExpr(
                            null, StaticJavaParser.parseClassOrInterfaceType(model.generatedName()), arguments);
                }
            } catch (Unsupported projectionMissing) {
            }
            throw new Unsupported("Imperative code constructs a game object: " + creation);
        }

        @Override
        public Visitable visit(CastExpr cast, Void ignored) {
            String originalType = cast.getTypeAsString();
            super.visit(cast, ignored);
            if (!cast.getType().isPrimitiveType())
                cast.setType(StaticJavaParser.parseType(valueType(originalType).type()));
            return cast;
        }

        @Override
        public Visitable visit(ArrayCreationExpr creation, Void ignored) {
            String component = creation.getElementType().asString();
            if (creation.getLevels().size() != 1
                    || creation.getLevels().get(0).getDimension().isEmpty())
                throw new Unsupported("Array allocation is not a bounded one-dimensional wire array");
            Expression count = creation.getLevels().get(0).getDimension().get();
            if (!(count instanceof IntegerLiteralExpr literal
                    && literal.asNumber().intValue() >= 0
                    && literal.asNumber().intValue() <= 65536)) {
                if (encode || !(count instanceof NameExpr name) || !boundedArrayCounts.contains(name.getNameAsString()))
                    throw new Unsupported("Array allocation count has no proven input-byte bound");
                creation.getLevels()
                        .get(0)
                        .setDimension(
                                expression("WireCodecs.boundedCount(" + count + ", " + buffer + ".readableBytes())"));
            }
            Wire wire = valueType(component);
            creation.setElementType(StaticJavaParser.parseType(wire.type()));
            return creation;
        }

        @Override
        public Visitable visit(VariableDeclarationExpr declaration, Void ignored) {
            super.visit(declaration, ignored);
            for (var variable : declaration.getVariables()) {
                Wire wire = valueType(variable.getTypeAsString());
                variable.setType(StaticJavaParser.parseType(wire.type()));
            }
            return declaration;
        }
    }

    private SourceIndex.Source helperOwner(String scope, String method) {
        if (!scope.isEmpty()) return analyzer.index.source(analyzer.index.resolve(scope, source));
        SourceIndex.Source target = source;
        while (true) {
            if (!target.declaration().getMethodsByName(method).isEmpty()) return target;
            if (target.declaration() instanceof ClassOrInterfaceDeclaration clazz
                    && clazz.getExtendedTypes().size() == 1) {
                var parent = analyzer.index.source(
                        analyzer.index.resolve(clazz.getExtendedTypes().get(0).getNameWithScope(), target));
                if (!parent.declaration().getMethodsByName(method).isEmpty()) return parent;
            }
            int dot = target.name().lastIndexOf('.');
            if (dot < 0) break;
            String enclosing = target.name().substring(0, dot);
            if (enclosing.equals(target.unit()
                    .getPackageDeclaration()
                    .orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))
                    .getNameAsString())) break;
            target = analyzer.index.source(enclosing);
        }
        throw new Unsupported("Unknown imperative helper: " + method);
    }

    private Wire fieldWire(Expression expression) {
        if (expression instanceof FieldAccessExpr field
                && (field.getScope() instanceof ThisExpr
                        || field.getScope().toString().equals(valueName))
                && fields.containsKey(field.getNameAsString())) return fields.get(field.getNameAsString());
        throw new Unsupported("Not a known imperative field: " + expression);
    }

    private static String physical(String shape) {
        return shape.equals("UNSIGNED_BYTE") ? "BYTE" : shape.equals("UNSIGNED_SHORT") ? "SHORT" : shape;
    }

    private static Expression expression(String code) {
        return StaticJavaParser.parseExpression(code);
    }

    private static boolean buffer(String name) {
        return Set.of("ByteBuf", "FriendlyByteBuf", "RegistryFriendlyByteBuf").contains(name);
    }

    private static String local(String field) {
        return "$packetforge_" + field;
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String contents(BlockStmt block) {
        return String.join(
                "\n", block.getStatements().stream().map(Object::toString).toList());
    }

    private static String defaultValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0f";
            case "double" -> "0d";
            default -> "null";
        };
    }
}
