package org.stackanvil.packetforge.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Translates imperative serialization while retaining branches, locals, and bit operations. */
final class ProgramLowerer {
    record Lowered(List<Field> fields, Program program) {}
    private final Analyzer analyzer;
    private final SourceIndex.Source source;
    private final Map<String, Wire> fields = new LinkedHashMap<>();
    private final Set<String> reads = new TreeSet<>(), writes = new TreeSet<>();
    private final Map<String, String> originalTypes = new LinkedHashMap<>();
    private final Map<String, String> helperCode = new LinkedHashMap<>();
    private final Map<String, String> localTypes = new HashMap<>();
    private String valueName = "value";
    ProgramLowerer(Analyzer analyzer, SourceIndex.Source source) { this.analyzer = analyzer; this.source = source; }

    Lowered lower(Expression codec) {
        BlockStmt decode, encode;
        String input, output;
        boolean constructorMode;
        if (codec instanceof ObjectCreationExpr anonymous && anonymous.getAnonymousClassBody().isPresent()) {
            var members = anonymous.getAnonymousClassBody().get();
            var readers = members.stream().filter(m -> m instanceof MethodDeclaration method && method.getNameAsString().equals("decode")).map(m -> (MethodDeclaration) m).toList();
            var writers = members.stream().filter(m -> m instanceof MethodDeclaration method && method.getNameAsString().equals("encode")).map(m -> (MethodDeclaration) m).toList();
            if (readers.size() != 1 || writers.size() != 1) throw new Unsupported("Ambiguous anonymous stream codec");
            var reader = readers.getFirst(); var writer = writers.getFirst();
            decode = reader.getBody().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).clone(); encode = writer.getBody().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).clone();
            input = reader.getParameter(0).getNameAsString(); output = writer.getParameter(0).getNameAsString(); valueName = writer.getParameter(1).getNameAsString(); constructorMode = false;
        } else if (codec instanceof MethodCallExpr call && Set.of("codec", "ofMember", "of").contains(call.getNameAsString()) && call.getArguments().size() == 2 && call.getArgument(0) instanceof MethodReferenceExpr writer && call.getArgument(1) instanceof MethodReferenceExpr reader) {
            var writers = source.declaration().getMethodsByName(writer.getIdentifier()).stream().filter(m -> (m.getParameters().size() == 1 || m.getParameters().size() == 2) && buffer(m.getParameter(0).getTypeAsString())).toList();
            if (writers.size() != 1) throw new Unsupported("Ambiguous imperative writer");
            var write = writers.getFirst(); output = write.getParameter(0).getNameAsString(); encode = write.getBody().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).clone();
            if (write.getParameters().size() == 2) valueName = write.getParameter(1).getNameAsString();
            constructorMode = reader.getIdentifier().equals("new");
            if (constructorMode) {
                var constructors = source.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration c && c.getParameters().size() == 1 && buffer(c.getParameter(0).getTypeAsString())).map(m -> (ConstructorDeclaration) m).toList();
                if (constructors.size() != 1) throw new Unsupported("Ambiguous imperative constructor");
                var constructor = constructors.getFirst(); input = constructor.getParameter(0).getNameAsString(); decode = constructor.getBody().clone();
            } else {
                var readers = source.declaration().getMethodsByName(reader.getIdentifier()).stream().filter(m -> m.getParameters().size() == 1 && buffer(m.getParameter(0).getTypeAsString())).toList();
                if (readers.size() != 1) throw new Unsupported("Ambiguous imperative reader");
                var read = readers.getFirst(); input = read.getParameter(0).getNameAsString(); decode = read.getBody().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).clone();
            }
        } else throw new Unsupported("Not an imperative stream codec");
        if (!decode.findAll(ExplicitConstructorInvocationStmt.class).isEmpty()) throw new Unsupported("Imperative constructor delegates");
        collectTypes(source, new HashSet<>());
        for (var variable : decode.findAll(VariableDeclarator.class)) localTypes.put(variable.getNameAsString(), variable.getTypeAsString());
        Set<String> used = new HashSet<>();
        for (var access : decode.findAll(FieldAccessExpr.class)) if (access.getScope() instanceof ThisExpr) used.add(access.getNameAsString());
        for (var access : encode.findAll(FieldAccessExpr.class)) if (access.getScope() instanceof ThisExpr || access.getScope().toString().equals(valueName)) used.add(access.getNameAsString());
        for (var call : encode.findAll(MethodCallExpr.class)) if (call.getScope().map(Object::toString).orElse("").equals(valueName) && originalTypes.containsKey(call.getNameAsString())) used.add(call.getNameAsString());
        for (var entry : originalTypes.entrySet()) if (used.contains(entry.getKey())) fields.put(entry.getKey(), valueType(entry.getValue()));
        if (!fields.keySet().containsAll(used)) throw new Unsupported("Imperative layout uses unknown fields");
        for (var assignment : decode.findAll(AssignExpr.class)) {
            if (assignment.getTarget() instanceof FieldAccessExpr target && target.getScope() instanceof ThisExpr) {
                try { fields.put(target.getNameAsString(), analyzer.read(assignment.getValue(), input, source)); }
                catch (Unsupported ignored) { }
            }
        }
        decode.accept(new Translator(input, false), null);
        encode.accept(new Translator(output, true), null);
        if (!reads.equals(writes) || reads.isEmpty()) throw new Unsupported("Imperative reader/writer wire operations differ: " + reads + " / " + writes);
        List<Field> layout = fields.entrySet().stream().map(entry -> new Field(entry.getKey(), originalTypes.get(entry.getKey()), entry.getValue(), entry.getValue())).toList();
        StringBuilder body = new StringBuilder();
        if (constructorMode) for (var field : layout) body.append(field.read().type()).append(" ").append(local(field.name())).append(" = ").append(defaultValue(field.read().type())).append(";\n");
        body.append(contents(decode));
        if (constructorMode) body.append("\nreturn new ").append(simple(analyzer.index.generated(source.name()))).append("(").append(String.join(", ", fields.keySet().stream().map(ProgramLowerer::local).toList())).append(");");
        return new Lowered(layout, new Program(body.toString(), contents(encode), List.copyOf(reads), List.copyOf(helperCode.values())));
    }
    private void collectTypes(SourceIndex.Source target, Set<String> visited) {
        if (!visited.add(target.name())) return;
        if (target.declaration() instanceof ClassOrInterfaceDeclaration clazz) for (var parent : clazz.getExtendedTypes()) collectTypes(analyzer.index.source(analyzer.index.resolve(parent.getNameWithScope(), target)), visited);
        if (target.declaration() instanceof RecordDeclaration record) for (var parameter : record.getParameters()) originalTypes.put(parameter.getNameAsString(), parameter.getTypeAsString());
        for (var declaration : target.declaration().getFields()) if (!declaration.isStatic()) for (var variable : declaration.getVariables()) originalTypes.put(variable.getNameAsString(), variable.getTypeAsString());
    }
    private Map<String, Expression> constructedFields(SourceIndex.Source target, NodeList<Expression> arguments, Set<String> visited) {
        if (!visited.add(target.name() + "#" + arguments.size())) throw new Unsupported("Recursive constructor delegation");
        if (target.declaration() instanceof RecordDeclaration record && record.getParameters().size() == arguments.size()) {
            Map<String, Expression> result = new HashMap<>(); for (int i = 0; i < arguments.size(); i++) result.put(record.getParameter(i).getNameAsString(), arguments.get(i).clone()); return result;
        }
        var constructors = target.declaration().getMembers().stream().filter(m -> m instanceof ConstructorDeclaration c && c.getParameters().size() == arguments.size()).map(m -> (ConstructorDeclaration) m).filter(c -> {
            for (int i = 0; i < arguments.size(); i++) { String actual = expressionType(arguments.get(i)); if (actual != null && !simple(actual).equals(simple(c.getParameter(i).getTypeAsString()))) return false; }
            return true;
        }).toList();
        if (constructors.size() != 1) throw new Unsupported("Ambiguous value constructor");
        var constructor = constructors.getFirst(); Map<String, Expression> parameters = new HashMap<>(), result = new HashMap<>();
        for (int i = 0; i < arguments.size(); i++) parameters.put(constructor.getParameter(i).getNameAsString(), arguments.get(i));
        for (var statement : constructor.getBody().getStatements()) {
            if (statement instanceof ExplicitConstructorInvocationStmt invocation && invocation.isThis()) {
                NodeList<Expression> values = new NodeList<>(); invocation.getArguments().forEach(e -> values.add(substitute(e, parameters)));
                result.putAll(constructedFields(target, values, visited));
            } else if (statement instanceof ExplicitConstructorInvocationStmt invocation && !invocation.isThis() && target.declaration() instanceof ClassOrInterfaceDeclaration clazz && clazz.getExtendedTypes().size() == 1) {
                var parent = analyzer.index.source(analyzer.index.resolve(clazz.getExtendedTypes().get(0).getNameWithScope(), target));
                NodeList<Expression> values = new NodeList<>(); invocation.getArguments().forEach(e -> values.add(substitute(e, parameters)));
                result.putAll(constructedFields(parent, values, visited));
            } else if (statement instanceof ExpressionStmt expression && expression.getExpression() instanceof AssignExpr assignment && assignment.getTarget() instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr && assignment.getOperator() == AssignExpr.Operator.ASSIGN) result.put(field.getNameAsString(), substitute(assignment.getValue(), parameters));
            else throw new Unsupported("Constructor contains non-assignment behavior");
        }
        return result;
    }
    private String expressionType(Expression expression) {
        if (expression instanceof NameExpr name) return localTypes.get(name.getNameAsString());
        if (expression instanceof ObjectCreationExpr created) return created.getTypeAsString();
        if (expression instanceof CastExpr cast) return cast.getTypeAsString();
        if (expression instanceof IntegerLiteralExpr || expression instanceof BinaryExpr binary && !Set.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS).contains(binary.getOperator())) return "int";
        if (expression instanceof BooleanLiteralExpr) return "boolean";
        if (expression instanceof MethodCallExpr call) {
            try {
                var target = helperOwner(call.getScope().map(Object::toString).orElse(""), call.getNameAsString());
                var methods = target.declaration().getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
                if (methods.size() == 1) return methods.getFirst().getTypeAsString();
            } catch (Unsupported ignored) { }
        }
        return null;
    }
    private static Expression substitute(Expression expression, Map<String, Expression> parameters) {
        return (Expression) expression.clone().accept(new ModifierVisitor<Void>() {
            @Override public Visitable visit(NameExpr name, Void ignored) { return parameters.containsKey(name.getNameAsString()) ? parameters.get(name.getNameAsString()).clone() : name; }
        }, null);
    }
    private Wire valueType(String name) {
        name = name.replaceAll("@[A-Za-z0-9_.]+(?:\\([^)]*\\))?\\s*", "").strip();
        String codec = switch (name) {
            case "boolean" -> "BOOL"; case "byte" -> "BYTE"; case "short" -> "SHORT"; case "int" -> "INT";
            case "long" -> "LONG"; case "float" -> "FLOAT"; case "double" -> "DOUBLE"; default -> null;
        };
        if (codec != null) return new Wire(name, "WireCodecs." + codec, codec);
        if (name.equals("String")) return new Wire("String", "WireCodecs.STRING", "UTF8");
        if (name.equals("Identifier")) return new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        if (name.equals("BlockPos")) return new Wire("org.stackanvil.packetforge.commons.BlockPosition", "WireCodecs.BLOCK_POSITION", "BLOCK_POSITION");
        if (name.equals("CompoundTag")) return new Wire("org.stackanvil.packetforge.commons.NbtTag.Compound", "NbtCodecs.NULLABLE_COMPOUND", "NBT_COMPOUND_NULLABLE");
        if (name.equals("Tag") || name.equals("Component")) return new Wire("org.stackanvil.packetforge.commons.NbtTag", "NbtCodecs.TAG", name.equals("Component") ? "NBT:net.minecraft.network.chat.ComponentSerialization#CODEC" : "NBT");
        if (name.equals("UUID")) return new Wire("java.util.UUID", "WireCodecs.UUID_CODEC", "UUID_CODEC");
        if (name.equals("byte[]")) return new Wire("byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
        var target = analyzer.index.source(analyzer.index.resolve(name, source));
        if (target.declaration() instanceof EnumDeclaration) {
            Model enumeration = analyzer.model(target.name());
            return new Wire(enumeration.generatedName(), "WireCodecs.enumOrdinal(" + enumeration.generatedName() + ".values())", "enum:" + enumeration.originalName());
        }
        return modelWire(analyzer.model(target.name()));
    }
    private final class Translator extends ModifierVisitor<Void> {
        final String buffer; final boolean encode;
        Translator(String buffer, boolean encode) { this.buffer = buffer; this.encode = encode; }
        @Override public Visitable visit(MethodCallExpr call, Void ignored) {
            MethodCallExpr original = call.clone();
            String scope = original.getScope().map(Object::toString).orElse("");
            String method = original.getNameAsString();
            if (scope.equals(buffer)) {
                if (method.equals("readBytes") && !encode && call.getArguments().size() == 1) {
                    reads.add("FIXED_BYTES"); return expression("WireCodecs.readFixedInto(" + buffer + ", " + call.getArgument(0) + ")");
                }
                if (method.equals("writeBytes") && encode && call.getArguments().size() == 1) {
                    writes.add("FIXED_BYTES"); Expression value = (Expression) call.getArgument(0).accept(this, null);
                    return expression("WireCodecs.fixedBytes((" + value + ").length).encode(" + buffer + ", " + value + ")");
                }
                if (!encode && method.startsWith("read")) {
                    Wire wire = analyzer.read(original, buffer, source); reads.add(physical(wire.shape()));
                    String code = wire.codec() + ".decode(" + buffer + ")";
                    if (method.equals("readUnsignedByte")) code = "(short)(int)(" + code + ")";
                    return expression(code);
                }
                if (encode && method.startsWith("write") && !call.getArguments().isEmpty()) {
                    String suffix = method.substring(5);
                    Wire wire;
                    if (suffix.equals("Enum")) {
                        wire = fieldWire(original.getArgument(0));
                        if (!wire.shape().startsWith("enum:")) throw new Unsupported("Imperative enum write is not an enum field");
                    } else {
                        String readMethod = method.replaceFirst("write", "read");
                        MethodCallExpr read = new MethodCallExpr(new NameExpr("input"), readMethod);
                        for (int i = 1; i < original.getArguments().size(); i++) read.addArgument(original.getArgument(i).clone());
                        wire = analyzer.read(read, "input", source);
                    }
                    writes.add(physical(wire.shape()));
                    call.setArgument(0, (Expression) call.getArgument(0).accept(this, null));
                    String argument = call.getArgument(0).toString();
                    if (Set.of("BYTE", "SHORT").contains(wire.shape())) argument = "(" + wire.type() + ")(" + argument + ")";
                    return expression(wire.codec() + ".encode(" + buffer + ", " + argument + ")");
                }
                throw new Unsupported("Unsupported imperative buffer operation: " + original);
            }
            if (Set.of("VarInt", "VarLong").contains(scope) && method.equals("read") && original.getArguments().size() == 1 && original.getArgument(0).toString().equals(buffer) && !encode) return new MethodCallExpr(new NameExpr(buffer), "read" + scope).accept(this, null);
            if (Set.of("VarInt", "VarLong").contains(scope) && method.equals("write") && original.getArguments().size() == 2 && original.getArgument(0).toString().equals(buffer) && encode) return new MethodCallExpr(new NameExpr(buffer), "write" + scope, new NodeList<>(original.getArgument(1).clone())).accept(this, null);
            if (scope.equals(valueName) && encode && call.getArguments().isEmpty() && fields.containsKey(method)) { call.setScope(new NameExpr("value")); return call; }
            if (method.equals("decode") && original.getArguments().size() == 1 && original.getArgument(0).toString().equals(buffer) && !encode) {
                Wire wire = analyzer.codec(original.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")), source); reads.add(physical(wire.shape()));
                return expression(wire.codec() + ".decode(" + buffer + ")");
            }
            if (method.equals("encode") && original.getArguments().size() == 2 && original.getArgument(0).toString().equals(buffer) && encode) {
                Wire wire = analyzer.codec(original.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")), source); writes.add(physical(wire.shape()));
                call.setArgument(1, (Expression) call.getArgument(1).accept(this, null));
                return expression(wire.codec() + ".encode(" + buffer + ", " + call.getArgument(1) + ")");
            }
            // Enum.ordinal is a JDK operation on generated enums.
            if (method.equals("ordinal") && call.getArguments().isEmpty() && fieldWire(original.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration"))).shape().startsWith("enum:")) {
                call.setScope((Expression) call.getScope().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).accept(this, null)); return call;
            }
            SourceIndex.Source target = helperOwner(scope, method);
            var candidates = target.declaration().getMethodsByName(method).stream().filter(m -> m.isStatic() && m.getParameters().size() == original.getArguments().size()).toList();
            if (candidates.size() != 1) throw new Unsupported("Ambiguous imperative helper: " + original);
            var helper = candidates.getFirst();
            if (!encode && helper.getParameters().size() == 1 && buffer(helper.getParameter(0).getTypeAsString()) && original.getArgument(0).toString().equals(buffer) && analyzer.index.resolve(helper.getTypeAsString(), target).equals(target.name())) {
                Wire wire = modelWire(analyzer.model(target.name())); reads.add(physical(wire.shape())); return expression(wire.codec() + ".decode(" + buffer + ")");
            }
            if (encode && helper.getParameters().size() == 2 && buffer(helper.getParameter(0).getTypeAsString()) && original.getArgument(0).toString().equals(buffer) && analyzer.index.resolve(helper.getParameter(1).getTypeAsString(), target).equals(target.name())) {
                Wire wire = modelWire(analyzer.model(target.name())); writes.add(physical(wire.shape())); Expression value = (Expression) original.getArgument(1).accept(this, null); return expression(wire.codec() + ".encode(" + buffer + ", " + value + ")");
            }
            String key = target.name() + ":" + method;
            String generated = "$helper_" + Integer.toUnsignedString(key.hashCode()) + "_" + method;
            if (!helperCode.containsKey(key)) {
                if (!helper.getType().isPrimitiveType() || helper.getParameters().stream().anyMatch(p -> !p.getType().isPrimitiveType())) throw new Unsupported("Imperative helper has domain object parameters");
                helperCode.put(key, "");
                MethodDeclaration lowered = helper.clone(); lowered.setName(generated).setPublic(false).setPrivate(true).setProtected(false);
                lowered.getBody().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).accept(new Translator("$noBuffer", false), null); helperCode.put(key, lowered.toString());
            }
            NodeList<Expression> values = new NodeList<>(); for (Expression argument : original.getArguments()) values.add((Expression) argument.accept(this, null));
            return new MethodCallExpr(null, generated, values);
        }
        @Override public Visitable visit(FieldAccessExpr field, Void ignored) {
            FieldAccessExpr original = field.clone();
            if (original.getNameAsString().equals("length")) { field.setScope((Expression) field.getScope().accept(this, null)); return field; }
            if (original.getScope() instanceof ThisExpr || original.getScope().toString().equals(valueName)) {
                if (!fields.containsKey(original.getNameAsString())) throw new Unsupported("Unknown imperative field: " + original);
                return encode ? expression("value." + original.getNameAsString() + "()") : new NameExpr(local(original.getNameAsString()));
            }
            try {
                String owner = analyzer.index.resolve(original.getScope().toString(), source);
                var target = analyzer.index.source(owner);
                if (target.declaration() instanceof EnumDeclaration enumeration && enumeration.getEntries().stream().anyMatch(c -> c.getNameAsString().equals(original.getNameAsString()))) return expression(analyzer.index.generated(owner) + "." + original.getNameAsString());
            } catch (Unsupported ignoredResolution) { }
            throw new Unsupported("Unknown imperative constant: " + original);
        }
        @Override public Visitable visit(ObjectCreationExpr creation, Void ignored) {
            if (Set.of("IllegalArgumentException", "IllegalStateException").contains(creation.getTypeAsString())) { super.visit(creation, ignored); return creation; }
            if (!encode && analyzer.index.resolve(creation.getTypeAsString(), source).equals(source.name())) {
                Map<String, Expression> values = constructedFields(source, creation.getArguments(), new HashSet<>());
                NodeList<Expression> arguments = new NodeList<>();
                for (String field : fields.keySet()) {
                    Expression value = values.get(field); if (value == null) throw new Unsupported("Constructor does not initialize " + field);
                    arguments.add((Expression) value.accept(this, null));
                }
                return new ObjectCreationExpr(null, StaticJavaParser.parseClassOrInterfaceType(analyzer.index.generated(source.name())), arguments);
            }
            throw new Unsupported("Imperative code constructs a game object: " + creation);
        }
        @Override public Visitable visit(VariableDeclarationExpr declaration, Void ignored) {
            super.visit(declaration, ignored);
            for (var variable : declaration.getVariables()) {
                Wire wire = valueType(variable.getTypeAsString()); variable.setType(StaticJavaParser.parseType(wire.type()));
            }
            return declaration;
        }
    }
    private SourceIndex.Source helperOwner(String scope, String method) {
        if (!scope.isEmpty()) return analyzer.index.source(analyzer.index.resolve(scope, source));
        SourceIndex.Source target = source;
        while (true) {
            if (!target.declaration().getMethodsByName(method).isEmpty()) return target;
            if (target.declaration() instanceof ClassOrInterfaceDeclaration clazz && clazz.getExtendedTypes().size() == 1) {
                var parent = analyzer.index.source(analyzer.index.resolve(clazz.getExtendedTypes().get(0).getNameWithScope(), target));
                if (!parent.declaration().getMethodsByName(method).isEmpty()) return parent;
            }
            int dot = target.name().lastIndexOf('.');
            if (dot < 0) break;
            String enclosing = target.name().substring(0, dot);
            if (enclosing.equals(target.unit().getPackageDeclaration().orElseThrow(() -> new Unsupported("Incomplete serialization declaration")).getNameAsString())) break;
            target = analyzer.index.source(enclosing);
        }
        throw new Unsupported("Unknown imperative helper: " + method);
    }
    private Wire fieldWire(Expression expression) {
        if (expression instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr && fields.containsKey(field.getNameAsString())) return fields.get(field.getNameAsString());
        throw new Unsupported("Not a known imperative field: " + expression);
    }
    private static String physical(String shape) { return shape.equals("UNSIGNED_BYTE") ? "BYTE" : shape.equals("UNSIGNED_SHORT") ? "SHORT" : shape; }
    private static Expression expression(String code) { return StaticJavaParser.parseExpression(code); }
    private static boolean buffer(String name) { return Set.of("ByteBuf", "FriendlyByteBuf", "RegistryFriendlyByteBuf").contains(name); }
    private static String local(String field) { return "$packetforge_" + field; }
    private static String simple(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static String contents(BlockStmt block) { return String.join("\n", block.getStatements().stream().map(Object::toString).toList()); }
    private static String defaultValue(String type) { return switch (type) { case "boolean" -> "false"; case "byte", "short", "int" -> "0"; case "long" -> "0L"; case "float" -> "0f"; case "double" -> "0d"; default -> "null"; }; }
}
