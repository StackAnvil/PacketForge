package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Lowers a deliberately bounded AST language to independent wire codecs. */
final class Analyzer {
    record Wire(String type, String codec, String shape, String projection) {
        Wire(String type, String codec, String shape) {
            this(type, codec, shape, "");
        }
    }

    record DispatchCase(String key, Wire value, String name) {
        DispatchCase(String key, Wire value) {
            this(key, value, key);
        }
    }

    record Dispatch(
            String name,
            Wire key,
            List<DispatchCase> cases,
            String sourceOwner,
            String originalExpression,
            Wire implementation,
            String registry,
            String sourceCodecField,
            Wire fallback) {
        Dispatch(
                String name,
                Wire key,
                List<DispatchCase> cases,
                String sourceOwner,
                String originalExpression,
                Wire implementation,
                String registry,
                String sourceCodecField) {
            this(
                    name,
                    key,
                    cases,
                    sourceOwner,
                    originalExpression,
                    implementation,
                    registry,
                    sourceCodecField,
                    null);
        }

        Dispatch(
                String name,
                Wire key,
                List<DispatchCase> cases,
                String sourceOwner,
                String originalExpression,
                Wire implementation,
                String registry) {
            this(
                    name,
                    key,
                    cases,
                    sourceOwner,
                    originalExpression,
                    implementation,
                    registry,
                    "STREAM_CODEC");
        }

        Dispatch(
                String name,
                Wire key,
                List<DispatchCase> cases,
                String sourceOwner,
                String originalExpression,
                Wire implementation) {
            this(name, key, cases, sourceOwner, originalExpression, implementation, "");
        }
    }

    record RegistryGap(int id, String name, String reason) {}

    record Field(String name, String originalType, Wire read, Wire write) {}

    record Program(String decode, String encode, List<String> operations, List<String> helpers) {}

    record Model(
            String originalName,
            String generatedName,
            List<Field> fields,
            List<String> constants,
            boolean packet,
            List<String> validations,
            Program program,
            String factory,
            String sourceCodecField,
            String decodeFailure) {
        Model(
                String originalName,
                String generatedName,
                List<Field> fields,
                List<String> constants,
                boolean packet,
                List<String> validations,
                Program program,
                String factory,
                String sourceCodecField) {
            this(
                    originalName,
                    generatedName,
                    fields,
                    constants,
                    packet,
                    validations,
                    program,
                    factory,
                    sourceCodecField,
                    "");
        }

        Model(
                String originalName,
                String generatedName,
                List<Field> fields,
                List<String> constants,
                boolean packet,
                List<String> validations,
                Program program,
                String factory) {
            this(
                    originalName,
                    generatedName,
                    fields,
                    constants,
                    packet,
                    validations,
                    program,
                    factory,
                    "STREAM_CODEC");
        }

        Model(
                String originalName,
                String generatedName,
                List<Field> fields,
                List<String> constants,
                boolean packet,
                List<String> validations,
                Program program) {
            this(originalName, generatedName, fields, constants, packet, validations, program, "");
        }

        Model(
                String originalName,
                String generatedName,
                List<Field> fields,
                List<String> constants,
                boolean packet) {
            this(originalName, generatedName, fields, constants, packet, List.of(), null);
        }

        Model(
                String originalName,
                String generatedName,
                List<Field> fields,
                List<String> constants,
                boolean packet,
                List<String> validations) {
            this(originalName, generatedName, fields, constants, packet, validations, null);
        }
    }

    record ContextRule(
            String predicate, String allowedModel, String fallbackModel, String rejection) {}

    record Registration(
            String state,
            String flow,
            int id,
            String name,
            String originalClass,
            String codecExpression,
            String source,
            int line,
            boolean supported,
            String reason,
            String generatedClass,
            ContextRule contextRule) {
        Registration(
                String state,
                String flow,
                int id,
                String name,
                String originalClass,
                String codecExpression,
                String source,
                int line,
                boolean supported,
                String reason,
                String generatedClass) {
            this(
                    state,
                    flow,
                    id,
                    name,
                    originalClass,
                    codecExpression,
                    source,
                    line,
                    supported,
                    reason,
                    generatedClass,
                    null);
        }
    }

    private record Decoded(String name, Expression value) {}

    private static final Map<String, String> SCALARS =
            Map.ofEntries(
                    Map.entry("Boolean", "BOOL:boolean"),
                    Map.entry("Byte", "BYTE:byte"),
                    Map.entry("UnsignedByte", "UNSIGNED_BYTE:int"),
                    Map.entry("Short", "SHORT:short"),
                    Map.entry("UnsignedShort", "UNSIGNED_SHORT:int"),
                    Map.entry("Int", "INT:int"),
                    Map.entry("Long", "LONG:long"),
                    Map.entry("Float", "FLOAT:float"),
                    Map.entry("Double", "DOUBLE:double"),
                    Map.entry("VarInt", "VAR_INT:int"),
                    Map.entry("ContainerId", "VAR_INT:int"),
                    Map.entry("VarLong", "VAR_LONG:long"),
                    Map.entry("LongArray", "LONG_ARRAY:long[]"),
                    Map.entry("VarIntArray", "VAR_INT_ARRAY:int[]"),
                    Map.entry(
                            "ChunkPos",
                            "CHUNK_POSITION:org.stackanvil.packetforge.commons.ChunkPosition"),
                    Map.entry("UUID", "UUID_CODEC:java.util.UUID"),
                    Map.entry("Identifier", "IDENTIFIER:String"),
                    Map.entry(
                            "BlockPos",
                            "BLOCK_POSITION:org.stackanvil.packetforge.commons.BlockPosition"));
    private static final Map<String, String> CODEC_SCALARS =
            Map.ofEntries(
                    Map.entry("BOOL", "Boolean"),
                    Map.entry("BYTE", "Byte"),
                    Map.entry("SHORT", "Short"),
                    Map.entry("UNSIGNED_SHORT", "UnsignedShort"),
                    Map.entry("INT", "Int"),
                    Map.entry("LONG", "Long"),
                    Map.entry("FLOAT", "Float"),
                    Map.entry("DOUBLE", "Double"),
                    Map.entry("VAR_INT", "VarInt"),
                    Map.entry("VAR_LONG", "VarLong"),
                    Map.entry("CONTAINER_ID", "ContainerId"));
    final SourceIndex index;
    private final Path root;
    final Map<String, Model> models = new TreeMap<>();
    final Map<String, Dispatch> dispatches = new TreeMap<>();
    final Set<String> activeDispatches = new HashSet<>();
    final Map<String, List<RegistryGap>> registryGaps = new TreeMap<>();
    final Map<String, String> codecVariants = new HashMap<>();
    private final Map<String, Wire> bindings = new HashMap<>();

    NameExpr bind(Wire wire) {
        String name = "$wire_" + bindings.size();
        bindings.put(name, wire);
        return new NameExpr(name);
    }

    private final Map<String, String> failures = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final Map<String, List<String>> validations = new HashMap<>();

    Analyzer(Path sources) {
        root = sources;
        index = new SourceIndex(sources);
    }

    List<Registration> analyze() throws IOException {
        List<Registration> registrations = new ArrayList<>();
        try (var files = Files.walk(root.resolve("net/minecraft/network/protocol"))) {
            for (Path file :
                    files.filter(f -> f.getFileName().toString().endsWith("Protocols.java"))
                            .sorted()
                            .toList()) {
                String fqn =
                        root.relativize(file)
                                .toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replaceAll("\\.java$", "");
                var source = index.source(fqn);
                for (FieldDeclaration field : source.declaration().getFields())
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (variable.getInitializer().isEmpty()
                                || !(variable.getInitializer().get()
                                        instanceof MethodCallExpr call)) continue;
                        String method = call.getNameAsString();
                        if (!Set.of(
                                        "serverboundProtocol",
                                        "clientboundProtocol",
                                        "contextServerboundProtocol",
                                        "contextClientboundProtocol")
                                .contains(method)) continue;
                        if (call.getArguments().size() != 2
                                || !(call.getArgument(0) instanceof FieldAccessExpr state))
                            throw new Unsupported("Unknown protocol builder: " + call);
                        String flow =
                                method.toLowerCase(Locale.ROOT).contains("serverbound")
                                        ? "SERVERBOUND"
                                        : "CLIENTBOUND";
                        LambdaExpr lambda = call.getArgument(1).asLambdaExpr();
                        String builder = lambda.getParameter(0).getNameAsString();
                        if (!(lambda.getBody() instanceof ExpressionStmt expression))
                            throw new Unsupported("Protocol builder is not a fluent expression");
                        List<MethodCallExpr> chain = new ArrayList<>();
                        collectChain(expression.getExpression(), builder, chain);
                        int id = 0;
                        for (MethodCallExpr registration : chain) {
                            if (registration.getNameAsString().equals("withBundlePacket")) {
                                var delimiter = registration.getArgument(2).asObjectCreationExpr();
                                String name = index.resolve(delimiter.getType().asString(), source);
                                var delimiterSource = index.source(name);
                                String packetName = packetName(delimiterSource);
                                Model model =
                                        new Model(
                                                name,
                                                index.generated(name),
                                                List.of(),
                                                List.of(),
                                                true);
                                models.put(name, model);
                                registrations.add(
                                        new Registration(
                                                state.getNameAsString(),
                                                flow,
                                                id++,
                                                packetName,
                                                name,
                                                "bundle delimiter",
                                                fqn,
                                                registration.getBegin().orElseThrow().line,
                                                true,
                                                "",
                                                model.generatedName()));
                                continue;
                            }
                            if (!registration.getNameAsString().equals("addPacket"))
                                throw new Unsupported(
                                        "Unknown registration operation: "
                                                + registration.getNameAsString());
                            if (registration.getArguments().size() < 2
                                    || registration.getArguments().size() > 3)
                                throw new Unsupported("Unknown packet registration arity");
                            String key = registration.getArgument(0).toString();
                            Expression codec = registration.getArgument(1);
                            if (!(codec instanceof FieldAccessExpr access))
                                throw new Unsupported(
                                        "Registration codec is not a field: " + codec);
                            String className = index.resolve(access.getScope().toString(), source);
                            String packetName = packetName(key, source);
                            String reason = "";
                            Model model = null;
                            ContextRule contextRule = null;
                            try {
                                model = model(className, access.getNameAsString());
                                if (!model.packet())
                                    throw new Unsupported(
                                            "Registered type does not directly implement Packet");
                                if (registration.getArguments().size() == 3)
                                    contextRule =
                                            new ContextModifierAnalyzer(this)
                                                    .lower(
                                                            registration.getArgument(2),
                                                            source,
                                                            model);
                            } catch (Unsupported e) {
                                reason = e.getMessage();
                            }
                            registrations.add(
                                    new Registration(
                                            state.getNameAsString(),
                                            flow,
                                            id++,
                                            packetName,
                                            className,
                                            codec.toString(),
                                            fqn,
                                            registration.getBegin().orElseThrow().line,
                                            reason.isEmpty(),
                                            reason,
                                            reason.isEmpty() ? model.generatedName() : "",
                                            reason.isEmpty() ? contextRule : null));
                        }
                    }
            }
        }
        if (registrations.isEmpty()) throw new Unsupported("No protocol registrations found");
        Set<String> keys = new HashSet<>();
        for (var registration : registrations)
            if (!keys.add(
                    registration.state() + ":" + registration.flow() + ":" + registration.id()))
                throw new Unsupported("Duplicate protocol registration");
        List<Registration> checked = new ArrayList<>();
        for (var entry : registrations) {
            String missing =
                    entry.supported()
                            ? missingSchema(
                                    models.values().stream()
                                            .filter(
                                                    m ->
                                                            m.generatedName()
                                                                    .equals(entry.generatedClass()))
                                            .findFirst()
                                            .orElseThrow(),
                                    new HashSet<>())
                            : "";
            if (missing.isEmpty()
                    && entry.supported()
                    && entry.contextRule() != null
                    && !entry.contextRule().fallbackModel().isEmpty())
                missing =
                        missingSchema(
                                models.get(entry.contextRule().fallbackModel()), new HashSet<>());
            checked.add(
                    missing.isEmpty()
                            ? entry
                            : new Registration(
                                    entry.state(),
                                    entry.flow(),
                                    entry.id(),
                                    entry.name(),
                                    entry.originalClass(),
                                    entry.codecExpression(),
                                    entry.source(),
                                    entry.line(),
                                    false,
                                    missing,
                                    ""));
        }
        return List.copyOf(checked);
    }

    private String missingSchema(Model model, Set<String> visited) {
        if (!visited.add("model:" + model.originalName())) return "";
        String text = model.fields().toString() + " " + model.program();
        return missingSchema(text, visited);
    }

    private String missingSchema(String text, Set<String> visited) {
        var references =
                java.util.regex.Pattern.compile("GeneratedWireCodecs\\.(dispatch_[0-9]+)")
                        .matcher(text);
        while (references.find()) {
            String name = references.group(1);
            if (!visited.add(name)) continue;
            Dispatch definition = dispatches.get(name);
            if (definition == null) return "Unresolved recursive codec table: " + name;
            String missing = missingSchema(definition.implementation().codec(), visited);
            if (!missing.isEmpty()) return missing;
        }
        for (var candidate : models.values())
            if (java.util.regex.Pattern.compile(
                            "\\b"
                                    + java.util.regex.Pattern.quote(candidate.generatedName())
                                    + "\\b")
                    .matcher(text)
                    .find()) {
                String missing = missingSchema(candidate, visited);
                if (!missing.isEmpty()) return missing;
            }
        return "";
    }

    private void collectChain(Expression expression, String builder, List<MethodCallExpr> chain) {
        if (expression instanceof NameExpr name && name.getNameAsString().equals(builder)) return;
        if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty())
            throw new Unsupported("Unknown builder expression: " + expression);
        collectChain(call.getScope().get(), builder, chain);
        chain.add(call);
    }

    private String packetName(SourceIndex.Source source) {
        var method = source.declaration().getMethodsByName("type").getFirst();
        var value =
                method.getBody()
                        .orElseThrow()
                        .getStatement(0)
                        .asReturnStmt()
                        .getExpression()
                        .orElseThrow();
        return packetName(value.toString(), source);
    }

    private String packetName(String key, SourceIndex.Source context) {
        int dot = key.lastIndexOf('.');
        var source = index.source(index.resolve(key.substring(0, dot), context));
        Expression initializer =
                source.declaration()
                        .getFieldByName(key.substring(dot + 1))
                        .orElseThrow()
                        .getVariable(0)
                        .getInitializer()
                        .orElseThrow();
        if (!(initializer instanceof MethodCallExpr call)
                || call.getArguments().size() != 1
                || !(call.getArgument(0) instanceof StringLiteralExpr id))
            throw new Unsupported("Unknown packet type declaration: " + key);
        return "minecraft:" + id.asString();
    }

    private boolean isPacket(SourceIndex.Source source, Set<String> visited) {
        if (!visited.add(source.name())) return false;
        if (source.name().equals("net.minecraft.network.protocol.Packet")) return true;
        List<com.github.javaparser.ast.type.ClassOrInterfaceType> parents = new ArrayList<>();
        if (source.declaration() instanceof RecordDeclaration record)
            parents.addAll(record.getImplementedTypes());
        if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz) {
            parents.addAll(clazz.getImplementedTypes());
            parents.addAll(clazz.getExtendedTypes());
        }
        for (var parent : parents) {
            if (parent.getNameAsString().equals("Packet")) return true;
            try {
                if (isPacket(
                        index.source(index.resolve(parent.getNameWithScope(), source)), visited))
                    return true;
            } catch (Unsupported ignored) {
            }
        }
        return false;
    }

    Model model(String name) {
        return model(name, "STREAM_CODEC");
    }

    Model model(String name, String codecField) {
        String key = codecField.equals("STREAM_CODEC") ? name : name + "#" + codecField;
        String generated =
                index.generated(name) + (codecField.equals("STREAM_CODEC") ? "" : "_" + codecField);
        String previousVariant = codecVariants.put(name, key);
        try {
            return lowerModel(name, codecField, key, generated);
        } finally {
            if (previousVariant == null) codecVariants.remove(name);
            else codecVariants.put(name, previousVariant);
        }
    }

    private Model lowerModel(String name, String codecField, String key, String generated) {
        if (models.containsKey(key)) return models.get(key);
        if (failures.containsKey(key)) throw new Unsupported(failures.get(key));
        if (!active.add(key)) throw new Unsupported("Recursive codec dependency: " + name);
        try {
            var source = index.source(name);
            var declaration = source.declaration();
            if (declaration instanceof EnumDeclaration enumeration) {
                Model model =
                        new Model(
                                name,
                                index.generated(name),
                                List.of(),
                                enumeration.getEntries().stream()
                                        .map(EnumConstantDeclaration::getNameAsString)
                                        .toList(),
                                false);
                if (model.constants().isEmpty()) throw new Unsupported("Empty enum: " + name);
                models.put(key, model);
                return model;
            }
            boolean packet = isPacket(source, new HashSet<>());
            Expression codec =
                    declaration
                            .getFieldByName(codecField)
                            .map(f -> f.getVariable(0).getInitializer().orElseThrow())
                            .orElseGet(
                                    () -> {
                                        if (!codecField.equals("STREAM_CODEC"))
                                            throw new Unsupported(
                                                    "Missing named codec field: "
                                                            + name
                                                            + "."
                                                            + codecField);
                                        return StaticJavaParser.parseExpression(
                                                "Packet.codec("
                                                        + declaration.getNameAsString()
                                                        + "::write, "
                                                        + declaration.getNameAsString()
                                                        + "::new)");
                                    });
            List<Field> fields;
            Program program = null;
            String decodeFailure = "";
            List<String> extraGuards = List.of();
            try {
                if (name.equals("net.minecraft.network.protocol.game.ClientboundCommandsPacket")) {
                    var lowered = new CommandPacketAnalyzer(this).lower(codec, source);
                    fields = lowered.fields();
                    extraGuards = lowered.validations();
                } else if (name.equals(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")) {
                    var lowered = new ActionSetAnalyzer(this).lower(codec, source, generated);
                    fields = lowered.fields();
                    program = lowered.program();
                } else if (Set.of(
                                "net.minecraft.network.protocol.login.ClientboundCustomQueryPacket",
                                "net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket")
                        .contains(name)) {
                    fields = new LoginQueryAnalyzer(this).lower(codec, source);
                } else if (codec instanceof MethodCallExpr call
                        && call.getScope().map(Object::toString).orElse("").equals("StreamCodec")
                        && call.getNameAsString().equals("unit")) {
                    fields = List.of();
                } else if (codec instanceof MethodCallExpr call
                        && call.getScope().map(Object::toString).orElse("").equals("StreamCodec")
                        && call.getNameAsString().equals("composite")) {
                    fields = composite(call, source);
                } else if (codec instanceof MethodCallExpr call
                        && Set.of("Packet", "StreamCodec", "CustomPacketPayload")
                                .contains(call.getScope().map(Object::toString).orElse(""))
                        && Set.of("codec", "ofMember").contains(call.getNameAsString())) {
                    fields = memberCodec(call, source);
                } else if (codec instanceof MethodCallExpr call
                        && call.getNameAsString().equals("map")
                        && call.getArguments().size() == 2
                        && call.getArgument(0) instanceof MethodReferenceExpr constructor
                        && constructor.getIdentifier().equals("new")
                        && index.resolve(constructor.getScope().toString(), source).equals(name)) {
                    String field = getterField(call.getArgument(1), source);
                    Wire underlying = codec(call.getScope().orElseThrow(), source);
                    fields =
                            List.of(
                                    new Field(
                                            field,
                                            declaredType(field, source),
                                            underlying,
                                            underlying));
                } else if (codec instanceof NameExpr || codec instanceof FieldAccessExpr) {
                    Wire underlying = codec(codec, source);
                    if (underlying.shape().startsWith("model:"))
                        fields = models.get(underlying.shape().substring(6)).fields();
                    else throw new Unsupported("Codec alias does not construct this value");
                } else if (codec instanceof ObjectCreationExpr anonymous
                        && anonymous.getAnonymousClassBody().isPresent()) {
                    var lowered = new DelegatingCodecAnalyzer(this).lower(anonymous, source);
                    fields = lowered.fields();
                    decodeFailure = lowered.decodeFailure();
                } else if (codec instanceof MethodCallExpr factory
                        && factory.getScope().isEmpty()) {
                    Wire underlying =
                            new StaticCodecResolver(this)
                                    .factory(
                                            factory.clone()
                                                    .setScope(
                                                            new NameExpr(
                                                                    source.declaration()
                                                                            .getNameAsString())),
                                            source);
                    if (!underlying.shape().startsWith("model:"))
                        throw new Unsupported(
                                "Codec factory does not construct its declared model");
                    fields = models.get(underlying.shape().substring(6)).fields();
                } else {
                    throw new Unsupported("Unsupported codec declaration: " + shortText(codec));
                }
            } catch (Unsupported original) {
                if (Set.of(
                                "net.minecraft.network.protocol.game.ClientboundCommandsPacket",
                                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",
                                "net.minecraft.network.protocol.login.ClientboundCustomQueryPacket",
                                "net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket")
                        .contains(name)) throw original;
                if (codec instanceof MethodCallExpr composite
                        && composite
                                .getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("StreamCodec")
                        && composite.getNameAsString().equals("composite")) {
                    Wire projection = compositeWire(composite, source);
                    fields = models.get(projection.shape().substring("model:".length())).fields();
                } else
                    try {
                        try {
                            fields = new EnumOperationAnalyzer(this).lower(codec, source);
                        } catch (Unsupported operationFailure) {
                            if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz
                                    && clazz.isAbstract()) {
                                fields =
                                        new PolymorphicConstructorAnalyzer(this)
                                                .lower(codec, source);
                            } else {
                                var lowered =
                                        new ProgramLowerer(this, source, generated).lower(codec);
                                fields = lowered.fields();
                                program = lowered.program();
                            }
                        }
                    } catch (Unsupported programFailure) {
                        try {
                            fields = new LinearWireProjection(this).lower(codec, source);
                        } catch (Unsupported projectionFailure) {
                            if (!programFailure
                                    .getMessage()
                                    .equals("Not an imperative stream codec"))
                                throw new Unsupported(
                                        original.getMessage()
                                                + "; imperative: "
                                                + programFailure.getMessage()
                                                + "; projection: "
                                                + projectionFailure.getMessage());
                            throw original;
                        }
                    }
            }
            List<String> guards = new ArrayList<>(validations.getOrDefault(name, List.of()));
            guards.addAll(extraGuards);
            guards.addAll(recordGuards(source, fields));
            Model model =
                    new Model(
                            key,
                            generated,
                            fields,
                            List.of(),
                            packet,
                            List.copyOf(guards),
                            program,
                            "",
                            codecField,
                            decodeFailure);
            models.put(key, model);
            return model;
        } catch (Unsupported e) {
            failures.put(key, e.getMessage());
            throw e;
        } finally {
            active.remove(key);
        }
    }

    private List<Field> composite(MethodCallExpr call, SourceIndex.Source source) {
        var args = call.getArguments();
        if (args.isEmpty()
                || args.size() % 2 != 1
                || !(args.get(args.size() - 1) instanceof MethodReferenceExpr constructor)
                || !index.resolve(constructor.getScope().toString(), source).equals(source.name()))
            throw new Unsupported("Composite does not end in its constructor or factory");
        if (!constructor.getIdentifier().equals("new")) {
            var factories = source.declaration().getMethodsByName(constructor.getIdentifier());
            if (factories.size() != 1
                    || !factories.getFirst().isStatic()
                    || factories.getFirst().getBody().isEmpty())
                throw new Unsupported("Unknown composite factory");
            var body = factories.getFirst().getBody().get();
            if (body.getStatements().size() != 1
                    || !(body.getStatement(0) instanceof ReturnStmt returned)
                    || !(returned.getExpression().orElse(null)
                            instanceof ObjectCreationExpr created)
                    || !created.getArguments().stream()
                            .map(Object::toString)
                            .toList()
                            .equals(
                                    factories.getFirst().getParameters().stream()
                                            .map(p -> p.getNameAsString())
                                            .toList()))
                throw new Unsupported("Composite factory transforms its arguments");
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < args.size() - 1; i += 2) {
            String name = getterField(args.get(i + 1), source);
            String fieldType = declaredType(name, source);
            Wire wire = codec(args.get(i), source);
            fields.add(new Field(name, fieldType, wire, wire));
        }
        if (source.declaration() instanceof RecordDeclaration record
                && !record.getParameters().stream()
                        .map(p -> p.getNameAsString())
                        .toList()
                        .equals(fields.stream().map(Field::name).toList()))
            throw new Unsupported("Composite getter order differs from record constructor");
        return fields;
    }

    private List<Field> memberCodec(MethodCallExpr call, SourceIndex.Source source) {
        if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz
                && !clazz.getExtendedTypes().isEmpty())
            throw new Unsupported("Inherited packet/value layout: " + source.name());
        if (call.getArguments().size() != 2
                || !(call.getArgument(0) instanceof MethodReferenceExpr writer)
                || !(call.getArgument(1) instanceof MethodReferenceExpr reader))
            throw new Unsupported("Codec is not a writer/constructor pair");
        if (!reader.getIdentifier().equals("new")
                || !index.resolve(reader.getScope().toString(), source).equals(source.name())
                || !index.resolve(writer.getScope().toString(), source).equals(source.name()))
            throw new Unsupported("Codec reader is not its constructor");
        var constructors =
                source.declaration().getMembers().stream()
                        .filter(
                                x ->
                                        x instanceof ConstructorDeclaration c
                                                && c.getParameters().size() == 1
                                                && isBuffer(c.getParameter(0).getTypeAsString()))
                        .map(x -> (ConstructorDeclaration) x)
                        .toList();
        if (constructors.size() != 1) throw new Unsupported("Expected one buffer constructor");
        var constructor = constructors.getFirst();
        String input = constructor.getParameter(0).getNameAsString();
        List<Decoded> decoded = new ArrayList<>();
        var statements = new ArrayList<>(constructor.getBody().getStatements());
        List<String> guards = new ArrayList<>();
        while (!statements.isEmpty()
                && statements.getLast() instanceof IfStmt guard
                && guard.getElseStmt().isEmpty()) {
            var thrown =
                    guard.getThenStmt().isBlockStmt()
                                    && guard.getThenStmt().asBlockStmt().getStatements().size() == 1
                            ? guard.getThenStmt().asBlockStmt().getStatement(0)
                            : guard.getThenStmt();
            if (!(thrown instanceof ThrowStmt throwing)
                    || !(throwing.getExpression() instanceof ObjectCreationExpr creation)
                    || !creation.getTypeAsString().equals("IllegalArgumentException")
                    || !guard.findAll(MethodCallExpr.class).isEmpty()) break;
            IfStmt copy = guard.clone();
            for (var access : new ArrayList<>(copy.findAll(FieldAccessExpr.class)))
                if (access.getScope() instanceof ThisExpr)
                    access.replace(new NameExpr(access.getNameAsString()));
            guards.addFirst(copy.toString());
            statements.removeLast();
        }
        validations.put(source.name(), List.copyOf(guards));
        if (statements.size() == 1
                && statements.get(0) instanceof ExplicitConstructorInvocationStmt invocation
                && invocation.isThis()
                && source.declaration() instanceof RecordDeclaration record) {
            if (invocation.getArguments().size() != record.getParameters().size())
                throw new Unsupported("Record constructor arity mismatch");
            for (int i = 0; i < record.getParameters().size(); i++)
                decoded.add(
                        new Decoded(
                                record.getParameter(i).getNameAsString(),
                                invocation.getArgument(i)));
        } else {
            for (Statement statement : statements) {
                if (!(statement instanceof ExpressionStmt expr)
                        || !(expr.getExpression() instanceof AssignExpr assignment)
                        || assignment.getOperator() != AssignExpr.Operator.ASSIGN)
                    throw new Unsupported(
                            "Reader has control flow or intermediate operations: "
                                    + shortText(statement));
                decoded.add(
                        new Decoded(directField(assignment.getTarget()), assignment.getValue()));
            }
        }
        List<MethodDeclaration> writers =
                source.declaration().getMethodsByName(writer.getIdentifier()).stream()
                        .filter(
                                m ->
                                        m.getParameters().size() == 1
                                                && isBuffer(m.getParameter(0).getTypeAsString()))
                        .toList();
        if (writers.size() != 1) throw new Unsupported("Expected one buffer writer");
        var method = writers.getFirst();
        String output = method.getParameter(0).getNameAsString();
        List<Field> fields = new ArrayList<>();
        var writes = method.getBody().orElseThrow().getStatements();
        if (writes.size() != decoded.size())
            throw new Unsupported("Reader/writer operation counts differ");
        for (int i = 0; i < writes.size(); i++) {
            Decoded read = decoded.get(i);
            if (!(writes.get(i) instanceof ExpressionStmt expr)
                    || !(expr.getExpression() instanceof MethodCallExpr write))
                throw new Unsupported("Writer has control flow or intermediate operations");
            Wire readWire = read(read.value(), input, source);
            Wire writeWire = write(write, output, read.name(), readWire, source);
            if (!readWire.shape().equals(writeWire.shape())
                    || !readWire.type().equals(writeWire.type()))
                throw new Unsupported("Reader/writer wire types differ for " + read.name());
            fields.add(
                    new Field(read.name(), declaredType(read.name(), source), readWire, writeWire));
        }
        Set<String> names = new HashSet<>();
        for (Field field : fields)
            if (!names.add(field.name())) throw new Unsupported("Repeated field assignment");
        if (source.declaration() instanceof RecordDeclaration record
                && record.getParameters().size() != fields.size())
            throw new Unsupported("Record contains fields outside its wire layout");
        return fields;
    }

    Wire read(Expression expression, String buffer, SourceIndex.Source source) {
        if (expression instanceof CastExpr cast) {
            if (cast.getType().isPrimitiveType())
                throw new Unsupported("Numeric cast changes wire value: " + shortText(expression));
            return read(cast.getExpression(), buffer, source);
        }
        if (expression instanceof ObjectCreationExpr creation
                && creation.getArguments().size() == 1
                && creation.getArgument(0).toString().equals(buffer))
            return modelWire(model(index.resolve(creation.getTypeAsString(), source)));
        if (expression instanceof MethodCallExpr helper
                && helper.getScope().isEmpty()
                && helper.getArguments().size() == 1
                && helper.getArgument(0).toString().equals(buffer))
            return new EntityMetadataAnalyzer(this).read(helper, buffer, source);
        if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty())
            throw new Unsupported("Unsupported read expression: " + shortText(expression));
        if (call.getNameAsString().equals("byName")
                && call.getArguments().size() == 2
                && call.getScope().orElse(null) instanceof FieldAccessExpr field
                && field.getNameAsString().equals("CODEC")) {
            var enumeration = index.source(index.resolve(field.getScope().toString(), source));
            Wire underlying = read(call.getArgument(0), buffer, source);
            if (!underlying.shape().equals("UTF8")
                    || !(enumeration.declaration() instanceof EnumDeclaration entries))
                throw new Unsupported("Named enum reader does not use a string codec");
            var declaration =
                    enumeration
                            .declaration()
                            .getFieldByName("CODEC")
                            .flatMap(f -> f.getVariable(0).getInitializer())
                            .orElseThrow();
            if (!(declaration instanceof MethodCallExpr factory)
                    || !factory.getScope()
                            .map(Object::toString)
                            .orElse("")
                            .equals("StringRepresentable")
                    || !factory.getNameAsString().equals("fromEnum")
                    || factory.getArguments().size() != 1
                    || !(factory.getArgument(0) instanceof MethodReferenceExpr values)
                    || !values.getIdentifier().equals("values")
                    || !index.resolve(values.getScope().toString(), enumeration)
                            .equals(enumeration.name()))
                throw new Unsupported(
                        "Enum codec is not StringRepresentable.fromEnum of its own constants");
            String nameField =
                    getterField(
                            new MethodReferenceExpr(
                                    new com.github.javaparser.ast.expr.TypeExpr(
                                            StaticJavaParser.parseType(enumeration.name())),
                                    null,
                                    "getSerializedName"),
                            enumeration);
            var constructors =
                    entries.getMembers().stream()
                            .filter(m -> m instanceof ConstructorDeclaration)
                            .map(m -> (ConstructorDeclaration) m)
                            .toList();
            List<String> names = new ArrayList<>();
            for (var constant : entries.getEntries()) {
                Expression name =
                        enumConstructorField(
                                constructors, constant.getArguments(), nameField, new HashSet<>());
                if (!(name instanceof StringLiteralExpr))
                    throw new Unsupported("Enum name is not a literal");
                names.add(name.toString());
            }
            if (!(call.getArgument(1) instanceof FieldAccessExpr fallback)
                    || !index.resolve(fallback.getScope().toString(), source)
                            .equals(enumeration.name())
                    || entries.getEntries().stream()
                            .noneMatch(e -> e.getNameAsString().equals(fallback.getNameAsString())))
                throw new Unsupported("Named enum fallback is not a constant of its type");
            Model model = model(enumeration.name());
            return new Wire(
                    model.generatedName(),
                    "WireCodecs.enumNames("
                            + model.generatedName()
                            + ".values(), new String[]{"
                            + String.join(", ", names)
                            + "}, "
                            + underlying.codec()
                            + ", "
                            + model.generatedName()
                            + "."
                            + fallback.getNameAsString()
                            + ")",
                    "enumString:" + enumeration.name());
        }
        if (call.getNameAsString().equals("decode")
                && call.getArguments().size() == 1
                && call.getArgument(0).toString().equals(buffer))
            return codec(call.getScope().get(), source);
        if (!call.getScope().get().toString().equals(buffer)
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof MethodCallExpr nested
                && nested.getScope().map(Object::toString).orElse("").equals(buffer)) {
            return enumSwitch(call, read(call.getArgument(0), buffer, source), source);
        }
        if (!call.getScope().get().toString().equals(buffer)
                || !call.getNameAsString().startsWith("read"))
            throw new Unsupported("Read invokes a helper or transform: " + shortText(call));
        String operation = call.getNameAsString().substring(4);
        if (SCALARS.containsKey(operation) && call.getArguments().isEmpty())
            return scalar(operation);
        if (Set.of("RegistryKey", "ResourceKey").contains(operation)
                && call.getArguments().isEmpty())
            return new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        if (operation.equals("Nbt") && call.getArguments().isEmpty())
            return new Wire(
                    "org.stackanvil.packetforge.commons.NbtTag.Compound",
                    "NbtCodecs.NULLABLE_COMPOUND",
                    "NBT_COMPOUND_NULLABLE");
        if (operation.equals("Utf") && call.getArguments().size() <= 1)
            return string(
                    call.getArguments().isEmpty() ? "32767" : integer(call.getArgument(0), source));
        if (operation.equals("ByteArray") && call.getArguments().size() <= 1)
            return new Wire(
                    "byte[]",
                    "WireCodecs.byteArray("
                            + (call.getArguments().isEmpty()
                                    ? "Integer.MAX_VALUE"
                                    : integer(call.getArgument(0), source))
                            + ")",
                    "byteArray");
        if (operation.equals("ById")
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof FieldAccessExpr lookup) {
            var target = index.source(index.resolve(lookup.getScope().toString(), source));
            if (!(target.declaration() instanceof EnumDeclaration))
                throw new Unsupported("ID reader is not a declared enum map");
            var initializer =
                    target.declaration()
                            .getFieldByName(lookup.getNameAsString())
                            .flatMap(f -> f.getVariable(0).getInitializer())
                            .orElseThrow(() -> new Unsupported("Missing enum ID lookup"));
            if (!(initializer instanceof MethodCallExpr factory)
                    || factory.getArguments().isEmpty())
                throw new Unsupported("Unknown enum ID factory");
            return enumIdMapper(
                    new MethodCallExpr(new NameExpr("ByteBufCodecs"), "idMapper")
                            .addArgument(new NameExpr(lookup.getNameAsString()))
                            .addArgument(factory.getArgument(0).clone()),
                    target);
        }
        if (operation.equals("Enum")
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof ClassExpr type) {
            Model enumeration = model(index.resolve(type.getTypeAsString(), source));
            if (enumeration.constants().isEmpty())
                throw new Unsupported("readEnum target is not an enum");
            return new Wire(
                    enumeration.generatedName(),
                    "WireCodecs.enumOrdinal(" + enumeration.generatedName() + ".values())",
                    "enum:" + enumeration.originalName());
        }
        if (operation.equals("Either") && call.getArguments().size() == 2)
            return either(
                    readerFunction(call.getArgument(0), source),
                    readerFunction(call.getArgument(1), source));
        if (Set.of("Nullable", "Optional", "List").contains(operation)
                && call.getArguments().size() == 1) {
            Wire inner = readerFunction(call.getArgument(0), source);
            return container(operation.toLowerCase(Locale.ROOT), inner, "Integer.MAX_VALUE");
        }
        throw new Unsupported("Unsupported buffer read: " + shortText(call));
    }

    Wire write(
            MethodCallExpr call,
            String buffer,
            String field,
            Wire read,
            SourceIndex.Source source) {
        if (read.shape().startsWith("metadata<")
                && new EntityMetadataAnalyzer(this).writes(call, buffer, field, source))
            return read;
        if (call.getScope().map(Object::toString).orElse("").equals(buffer)
                && call.getNameAsString().equals("writeById")
                && call.getArguments().size() == 2
                && directField(call.getArgument(1)).equals(field)
                && read.shape().startsWith("enum:")) {
            var target = index.source(read.shape().substring(5));
            if (!(call.getArgument(0) instanceof MethodReferenceExpr))
                throw new Unsupported("Enum ID writer is not a direct accessor");
            var lookup =
                    target.declaration().getFields().stream()
                            .flatMap(f -> f.getVariables().stream())
                            .filter(v -> v.getTypeAsString().startsWith("IntFunction<"))
                            .toList();
            if (lookup.size() != 1) throw new Unsupported("Ambiguous enum ID lookup");
            return enumIdMapper(
                    new MethodCallExpr(new NameExpr("ByteBufCodecs"), "idMapper")
                            .addArgument(new NameExpr(lookup.getFirst().getNameAsString()))
                            .addArgument(call.getArgument(0).clone()),
                    target);
        }
        if (call.getScope().isEmpty()) throw new Unsupported("Unscoped write");
        if (call.getNameAsString().equals("write")
                && call.getArguments().size() == 1
                && call.getArgument(0).toString().equals(buffer)
                && directField(call.getScope().get()).equals(field)
                && read.shape().startsWith("model:")) return read;
        if (call.getNameAsString().equals("encode")
                && call.getArguments().size() == 2
                && call.getArgument(0).toString().equals(buffer)
                && directField(call.getArgument(1)).equals(field))
            return codec(call.getScope().get(), source);
        if (read.shape().startsWith("enumString:")
                && call.getScope().get().toString().equals(buffer)
                && call.getNameAsString().equals("writeUtf")
                && call.getArguments().size() >= 1
                && call.getArgument(0) instanceof MethodCallExpr name
                && name.getNameAsString().equals("getSerializedName")
                && name.getArguments().isEmpty()
                && name.getScope().isPresent()
                && directField(name.getScope().get()).equals(field)) return read;
        if (read.shape().startsWith("enumSwitch:")
                && call.getScope().get().toString().equals(buffer)
                && call.getArguments().size() == 1
                && call.getArgument(0) instanceof MethodCallExpr accessor
                && accessor.getArguments().isEmpty()
                && accessor.getScope().isPresent()
                && directField(accessor.getScope().get()).equals(field)) {
            String[] parts = read.shape().split(":");
            String operation = call.getNameAsString().replaceFirst("^write", "");
            if (accessor.getNameAsString().equals(parts[2])
                    && SCALARS.containsKey(operation)
                    && scalar(operation).shape().equals(parts[3])) return read;
            throw new Unsupported("Enum ID reader/writer mapping differs");
        }
        if (!call.getScope().get().toString().equals(buffer)
                || !call.getNameAsString().startsWith("write")
                || call.getArguments().isEmpty()
                || !directField(call.getArgument(0)).equals(field))
            throw new Unsupported(
                    "Writer does not serialize the matching field directly: " + shortText(call));
        String operation = call.getNameAsString().substring(5);
        if (SCALARS.containsKey(operation) && call.getArguments().size() == 1) {
            Wire scalar = scalar(operation);
            // Netty writes signed and unsigned values with the same width.
            if (operation.equals("Byte") && read.shape().equals("UNSIGNED_BYTE"))
                return new Wire("int", "WireCodecs.UNSIGNED_BYTE", "UNSIGNED_BYTE");
            if (operation.equals("Short") && read.shape().equals("UNSIGNED_SHORT"))
                return new Wire("int", "WireCodecs.UNSIGNED_SHORT", "UNSIGNED_SHORT");
            return scalar;
        }
        if (operation.equals("Utf") && call.getArguments().size() <= 2)
            return string(
                    call.getArguments().size() == 1
                            ? "32767"
                            : integer(call.getArgument(1), source));
        if (operation.equals("ResourceKey") && call.getArguments().size() == 1)
            return new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        if (operation.equals("Nbt") && call.getArguments().size() == 1)
            return new Wire(
                    "org.stackanvil.packetforge.commons.NbtTag.Compound",
                    "NbtCodecs.NULLABLE_COMPOUND",
                    "NBT_COMPOUND_NULLABLE");
        if (operation.equals("Enum")
                && call.getArguments().size() == 1
                && read.shape().startsWith("enum:")) return read;
        if (operation.equals("ByteArray") && call.getArguments().size() == 1)
            return new Wire("byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
        if (operation.equals("Either") && call.getArguments().size() == 3)
            return either(
                    writerFunction(call.getArgument(1), source),
                    writerFunction(call.getArgument(2), source));
        if (Set.of("Nullable", "Optional", "Collection").contains(operation)
                && call.getArguments().size() == 2) {
            Wire inner = writerFunction(call.getArgument(1), source);
            return container(
                    operation.equals("Collection") ? "list" : operation.toLowerCase(Locale.ROOT),
                    inner,
                    "Integer.MAX_VALUE");
        }
        throw new Unsupported("Unsupported buffer write: " + shortText(call));
    }

    private Wire either(Wire left, Wire right) {
        return new Wire(
                "org.stackanvil.packetforge.commons.Either<"
                        + boxed(left.type())
                        + ", "
                        + boxed(right.type())
                        + ">",
                "WireCodecs.either(" + left.codec() + ", " + right.codec() + ")",
                "either<" + left.shape() + "," + right.shape() + ">");
    }

    private Wire enumIdMapper(MethodCallExpr call, SourceIndex.Source source) {
        String idField = enumIdField(call.getArgument(1), source);
        Expression lookup = call.getArgument(0);
        if (lookup instanceof MethodReferenceExpr reference
                && index.resolve(reference.getScope().toString(), source).equals(source.name())) {
            var methods = source.declaration().getMethodsByName(reference.getIdentifier());
            if (methods.size() == 1
                    && methods.getFirst().getParameters().size() == 1
                    && methods.getFirst().getBody().isPresent()) {
                var body = methods.getFirst().getBody().get();
                if (body.getStatements().size() == 1
                        && body.getStatement(0) instanceof ReturnStmt returned
                        && returned.getExpression().orElse(null) instanceof MethodCallExpr delegated
                        && delegated.getNameAsString().equals("apply")
                        && delegated.getArguments().size() == 1
                        && delegated
                                .getArgument(0)
                                .toString()
                                .equals(methods.getFirst().getParameter(0).getNameAsString()))
                    lookup = delegated.getScope().orElseThrow();
            }
        }
        if (!(lookup instanceof NameExpr byId))
            throw new Unsupported("Enum ID lookup is not a local constant");
        Expression initializer =
                source.declaration()
                        .getFieldByName(byId.getNameAsString())
                        .orElseThrow()
                        .getVariable(0)
                        .getInitializer()
                        .orElseThrow();
        if (!(initializer instanceof MethodCallExpr continuous)
                || !continuous.getScope().map(Object::toString).orElse("").equals("ByIdMap")
                || !Set.of("continuous", "sparse").contains(continuous.getNameAsString())
                || continuous.getArguments().size() != 3
                || !enumIdField(continuous.getArgument(0), source).equals(idField)
                || !isEnumValues(continuous.getArgument(1), source))
            throw new Unsupported("Enum ID lookup is not a matching continuous map");
        boolean sparse = continuous.getNameAsString().equals("sparse");
        String policy = continuous.getArgument(2).toString();
        String defaultConstant = sparse ? enumConstant(continuous.getArgument(2), source) : "";
        if (!sparse
                && !Set.of(
                                "ByIdMap.OutOfBoundsStrategy.ZERO",
                                "ByIdMap.OutOfBoundsStrategy.WRAP",
                                "ByIdMap.OutOfBoundsStrategy.CLAMP")
                        .contains(policy)) throw new Unsupported("Unknown enum ID fallback");
        var constructors =
                source.declaration().getMembers().stream()
                        .filter(m -> m instanceof ConstructorDeclaration)
                        .map(m -> (ConstructorDeclaration) m)
                        .toList();
        if (idField.equals("$ordinal")) {
            Model enumeration = model(source.name());
            return new Wire(
                    enumeration.generatedName(),
                    "WireCodecs.enumIds("
                            + enumeration.generatedName()
                            + ".values(), new int[]{"
                            + String.join(
                                    ", ",
                                    java.util.stream.IntStream.range(
                                                    0, enumeration.constants().size())
                                            .mapToObj(Integer::toString)
                                            .toList())
                            + "}, WireCodecs.VAR_INT, WireCodecs.EnumFallback."
                            + policy.substring(policy.lastIndexOf('.') + 1)
                            + ")",
                    "enum:" + source.name());
        }
        List<String> ids = new ArrayList<>();
        for (var constant : ((EnumDeclaration) source.declaration()).getEntries())
            ids.add(
                    integer(
                            enumConstructorField(
                                    constructors,
                                    constant.getArguments(),
                                    idField,
                                    new HashSet<>()),
                            source));
        if (!sparse
                && !ids.stream()
                        .map(Integer::parseInt)
                        .sorted()
                        .toList()
                        .equals(java.util.stream.IntStream.range(0, ids.size()).boxed().toList()))
            throw new Unsupported("Enum IDs are not continuous");
        Model enumeration = model(source.name());
        return new Wire(
                enumeration.generatedName(),
                "WireCodecs."
                        + (sparse ? "enumIdsOrDefault" : "enumIds")
                        + "("
                        + enumeration.generatedName()
                        + ".values(), new int[]{"
                        + String.join(", ", ids)
                        + "}, WireCodecs.VAR_INT, "
                        + (sparse
                                ? enumeration.generatedName() + "." + defaultConstant
                                : "WireCodecs.EnumFallback."
                                        + policy.substring(policy.lastIndexOf('.') + 1))
                        + ")",
                "enum:" + source.name());
    }

    private boolean isEnumValues(Expression expression, SourceIndex.Source source) {
        if (expression.toString().equals("values()")) return true;
        if (expression instanceof MethodCallExpr array
                && array.getNameAsString().equals("toArray")
                && array.getScope().orElse(null) instanceof NameExpr list) {
            var initializer =
                    source.declaration()
                            .getFieldByName(list.getNameAsString())
                            .flatMap(f -> f.getVariable(0).getInitializer())
                            .orElse(null);
            return initializer instanceof MethodCallExpr call
                    && call.getScope().map(Object::toString).orElse("").equals("List")
                    && call.getNameAsString().equals("of")
                    && call.getArguments().size() == 1
                    && call.getArgument(0).toString().equals("values()");
        }
        return false;
    }

    private String enumConstant(Expression expression, SourceIndex.Source source) {
        String name = expression.toString();
        if (source.declaration() instanceof EnumDeclaration e
                && e.getEntries().stream().anyMatch(c -> c.getNameAsString().equals(name)))
            return name;
        if (expression instanceof NameExpr)
            return enumConstant(
                    source.declaration()
                            .getFieldByName(name)
                            .flatMap(f -> f.getVariable(0).getInitializer())
                            .orElseThrow(() -> new Unsupported("Unknown enum default: " + name)),
                    source);
        throw new Unsupported("Enum default is not a constant: " + expression);
    }

    private Expression substitute(Expression expression, Map<String, Expression> variables) {
        if (expression instanceof NameExpr n && variables.containsKey(n.getNameAsString()))
            return variables.get(n.getNameAsString()).clone();
        Expression result = expression.clone();
        for (var name : result.findAll(NameExpr.class))
            if (variables.containsKey(name.getNameAsString()))
                name.replace(variables.get(name.getNameAsString()).clone());
        return result;
    }

    private Expression enumConstructorField(
            List<ConstructorDeclaration> constructors,
            List<Expression> args,
            String field,
            Set<ConstructorDeclaration> visited) {
        List<Expression> values = new ArrayList<>();
        for (var constructor : constructors) {
            if (constructor.getParameters().size() != args.size() || visited.contains(constructor))
                continue;
            Set<ConstructorDeclaration> next = new HashSet<>(visited);
            next.add(constructor);
            Map<String, Expression> parameters = new HashMap<>();
            for (int i = 0; i < args.size(); i++)
                parameters.put(constructor.getParameter(i).getNameAsString(), args.get(i));
            for (var statement : constructor.getBody().getStatements()) {
                if (statement instanceof ExplicitConstructorInvocationStmt delegation
                        && delegation.isThis())
                    values.add(
                            enumConstructorField(
                                    constructors,
                                    delegation.getArguments().stream()
                                            .map(e -> substitute(e, parameters))
                                            .toList(),
                                    field,
                                    next));
                if (statement instanceof ExpressionStmt expression
                        && expression.getExpression() instanceof AssignExpr assignment
                        && assignment.getTarget().toString().equals("this." + field)
                        && assignment.getOperator() == AssignExpr.Operator.ASSIGN)
                    values.add(substitute(assignment.getValue(), parameters));
            }
        }
        if (values.isEmpty() || values.stream().map(Object::toString).distinct().count() != 1)
            throw new Unsupported("Ambiguous enum constructor ID: " + field);
        return values.getFirst();
    }

    private String enumIdField(Expression expression, SourceIndex.Source source) {
        if (expression instanceof MethodReferenceExpr reference
                && reference.getScope().toString().equals("Enum")
                && reference.getIdentifier().equals("ordinal")) return "$ordinal";
        Expression value;
        String receiver;
        if (expression instanceof LambdaExpr lambda
                && lambda.getParameters().size() == 1
                && lambda.getBody() instanceof ExpressionStmt body) {
            value = body.getExpression();
            receiver = lambda.getParameter(0).getNameAsString();
        } else if (expression instanceof MethodReferenceExpr reference
                && index.resolve(reference.getScope().toString(), source).equals(source.name())) {
            var methods = source.declaration().getMethodsByName(reference.getIdentifier());
            if (methods.size() != 1 || !methods.getFirst().getParameters().isEmpty())
                throw new Unsupported("Ambiguous enum ID accessor");
            var body = methods.getFirst().getBody().orElseThrow();
            if (body.getStatements().size() != 1
                    || !(body.getStatement(0) instanceof ReturnStmt returned))
                throw new Unsupported("Enum ID accessor is not a direct field");
            value = returned.getExpression().orElseThrow();
            receiver = "this";
        } else throw new Unsupported("Unknown enum ID accessor");
        if (!(value instanceof FieldAccessExpr field)
                || !field.getScope().toString().equals(receiver))
            throw new Unsupported("Enum ID accessor transforms its field");
        var declaration =
                source.declaration().getFieldByName(field.getNameAsString()).orElseThrow();
        if (!declaration.isFinal() || !declaration.getVariable(0).getTypeAsString().equals("int"))
            throw new Unsupported("Enum ID is not a final integer");
        return field.getNameAsString();
    }

    private Wire enumSwitch(MethodCallExpr call, Wire inner, SourceIndex.Source context) {
        var source = index.source(index.resolve(call.getScope().orElseThrow().toString(), context));
        if (!(source.declaration() instanceof EnumDeclaration))
            throw new Unsupported("Read helper is not an enum mapping");
        var readers = source.declaration().getMethodsByName(call.getNameAsString());
        if (readers.size() != 1 || readers.getFirst().getParameters().size() != 1)
            throw new Unsupported("Ambiguous enum ID reader");
        var reader = readers.getFirst();
        SwitchExpr readSwitch = returnedSwitch(reader);
        if (!readSwitch.getSelector().toString().equals(reader.getParameter(0).getNameAsString()))
            throw new Unsupported("Enum reader switches on another value");
        Map<String, String> ids = new HashMap<>();
        boolean rejectsUnknown = false;
        for (SwitchEntry entry : readSwitch.getEntries()) {
            if (entry.getLabels().isEmpty()) {
                rejectsUnknown =
                        entry.getStatements().size() == 1
                                && entry.getStatement(0) instanceof ThrowStmt;
                continue;
            }
            if (entry.getLabels().size() != 1
                    || entry.getStatements().size() != 1
                    || !(entry.getStatement(0) instanceof ExpressionStmt value)
                    || !(value.getExpression() instanceof NameExpr name))
                throw new Unsupported("Complex enum ID reader");
            if (ids.put(name.getNameAsString(), integer(entry.getLabels().get(0), source)) != null)
                throw new Unsupported("Enum has duplicate wire IDs");
        }
        Model enumeration = model(source.name());
        if (!rejectsUnknown
                || !ids.keySet().equals(new HashSet<>(enumeration.constants()))
                || new HashSet<>(ids.values()).size() != ids.size())
            throw new Unsupported("Enum reader is not a one-to-one mapping");
        for (var method : source.declaration().getMethods()) {
            if (!method.getParameters().isEmpty() || !method.getTypeAsString().equals("int"))
                continue;
            SwitchExpr writeSwitch;
            try {
                writeSwitch = returnedSwitch(method);
            } catch (Unsupported e) {
                continue;
            }
            if (!(writeSwitch.getSelector() instanceof ThisExpr)) continue;
            Map<String, String> encoded = new HashMap<>();
            for (SwitchEntry entry : writeSwitch.getEntries()) {
                if (entry.getLabels().size() != 1
                        || entry.getStatements().size() != 1
                        || !(entry.getLabels().get(0) instanceof NameExpr label)
                        || !(entry.getStatement(0) instanceof ExpressionStmt value))
                    throw new Unsupported("Complex enum ID writer");
                encoded.put(label.getNameAsString(), integer(value.getExpression(), source));
            }
            if (encoded.equals(ids))
                return new Wire(
                        enumeration.generatedName(),
                        "WireCodecs.enumIds("
                                + enumeration.generatedName()
                                + ".values(), new int[]{"
                                + String.join(
                                        ", ",
                                        enumeration.constants().stream().map(ids::get).toList())
                                + "}, "
                                + inner.codec()
                                + ")",
                        "enumSwitch:"
                                + source.name()
                                + ":"
                                + method.getNameAsString()
                                + ":"
                                + inner.shape());
        }
        throw new Unsupported("Cannot prove matching enum ID writer");
    }

    private static SwitchExpr returnedSwitch(MethodDeclaration method) {
        var body = method.getBody().orElseThrow(() -> new Unsupported("Method has no body"));
        if (body.getStatements().size() != 1
                || !(body.getStatement(0) instanceof ReturnStmt returned)
                || !(returned.getExpression().orElse(null) instanceof SwitchExpr expression))
            throw new Unsupported("Method is not a direct switch expression");
        return expression;
    }

    private Wire readerFunction(Expression expression, SourceIndex.Source source) {
        if (expression instanceof LambdaExpr lambda
                && lambda.getParameters().size() == 1
                && lambda.getBody() instanceof ExpressionStmt body
                && body.getExpression() instanceof ObjectCreationExpr creation
                && index.resolve(creation.getTypeAsString(), source)
                        .equals("net.minecraft.world.phys.Vec3")
                && creation.getArguments().size() == 3
                && creation.getArguments().stream()
                        .allMatch(
                                a ->
                                        a.toString()
                                                .equals(
                                                        lambda.getParameter(0).getNameAsString()
                                                                + ".readDouble()")))
            return new Wire(
                    "org.stackanvil.packetforge.commons.Vector3", "VectorCodecs.DOUBLE", "VECTOR3");
        if (expression instanceof MethodReferenceExpr reference
                && reference.getIdentifier().equals("new"))
            return modelWire(model(index.resolve(reference.getScope().toString(), source)));
        if (expression instanceof MethodReferenceExpr reference
                && reference.getScope().toString().equals("FriendlyByteBuf")
                && reference.getIdentifier().startsWith("read"))
            return read(
                    new MethodCallExpr(new NameExpr("input"), reference.getIdentifier()),
                    "input",
                    source);
        return codec(expression, source);
    }

    private Wire writerFunction(Expression expression, SourceIndex.Source source) {
        if (expression instanceof LambdaExpr lambda
                && lambda.getParameters().size() == 2
                && lambda.getBody() instanceof BlockStmt body
                && body.getStatements().size() == 3) {
            String buffer = lambda.getParameter(0).getNameAsString(),
                    value = lambda.getParameter(1).getNameAsString();
            boolean vector = true;
            for (int i = 0; i < 3; i++)
                if (!body.getStatement(i)
                        .toString()
                        .equals(
                                buffer
                                        + ".writeDouble("
                                        + value
                                        + "."
                                        + List.of("x", "y", "z").get(i)
                                        + "());")) vector = false;
            if (vector)
                return new Wire(
                        "org.stackanvil.packetforge.commons.Vector3",
                        "VectorCodecs.DOUBLE",
                        "VECTOR3");
        }
        if (expression instanceof MethodReferenceExpr reference
                && reference.getIdentifier().equals("write")
                && !reference.getScope().toString().equals("FriendlyByteBuf"))
            return modelWire(model(index.resolve(reference.getScope().toString(), source)));
        if (expression instanceof MethodReferenceExpr reference
                && reference.getScope().toString().equals("FriendlyByteBuf")
                && reference.getIdentifier().startsWith("write")) {
            String operation = reference.getIdentifier().substring(5);
            if (SCALARS.containsKey(operation)) return scalar(operation);
            if (operation.equals("Utf")) return string("32767");
        }
        return codec(expression, source);
    }

    Wire codec(Expression expression, SourceIndex.Source source) {
        if (expression instanceof CastExpr cast) return codec(cast.getExpression(), source);
        if (expression instanceof EnclosedExpr enclosed) return codec(enclosed.getInner(), source);
        if (expression instanceof NameExpr bound && bindings.containsKey(bound.getNameAsString()))
            return bindings.get(bound.getNameAsString());
        if (expression instanceof ObjectCreationExpr anonymous
                && anonymous.getAnonymousClassBody().isPresent()) {
            var methods =
                    anonymous.getAnonymousClassBody().get().stream()
                            .filter(m -> m instanceof MethodDeclaration)
                            .map(m -> (MethodDeclaration) m)
                            .toList();
            var readers =
                    methods.stream().filter(m -> m.getNameAsString().equals("decode")).toList();
            var writers =
                    methods.stream().filter(m -> m.getNameAsString().equals("encode")).toList();
            if (readers.size() == 1 && writers.size() == 1) {
                var reader = readers.getFirst();
                var writer = writers.getFirst();
                var rb = reader.getBody().orElseThrow();
                var wb = writer.getBody().orElseThrow();
                if (rb.getStatements().size() == 1
                        && rb.getStatement(0) instanceof ReturnStmt returned
                        && returned.getExpression().orElse(null) instanceof MethodCallExpr read
                        && wb.getStatements().size() == 1
                        && wb.getStatement(0) instanceof ExpressionStmt statement
                        && statement.getExpression() instanceof MethodCallExpr write
                        && read.getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("FriendlyByteBuf")
                        && write.getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("FriendlyByteBuf")
                        && read.getNameAsString().startsWith("read")
                        && write.getNameAsString()
                                .equals(read.getNameAsString().replaceFirst("read", "write"))
                        && read.getArguments().size() >= 1
                        && read.getArgument(0)
                                .toString()
                                .equals(reader.getParameter(0).getNameAsString())
                        && write.getArguments().size() == 2
                        && write.getArgument(0)
                                .toString()
                                .equals(writer.getParameter(0).getNameAsString())
                        && write.getArgument(1)
                                .toString()
                                .equals(writer.getParameter(1).getNameAsString())) {
                    MethodCallExpr operation =
                            new MethodCallExpr(new NameExpr("input"), read.getNameAsString());
                    for (int i = 1; i < read.getArguments().size(); i++)
                        operation.addArgument(read.getArgument(i).clone());
                    return read(operation, "input", source);
                }
            }
        }
        if (expression instanceof FieldAccessExpr access) {
            String owner = index.resolve(access.getScope().toString(), source);
            String field = access.getNameAsString();
            if (owner.equals("net.minecraft.network.codec.ByteBufCodecs")) {
                if (CODEC_SCALARS.containsKey(field)) return scalar(CODEC_SCALARS.get(field));
                if (Set.of("TAG", "TRUSTED_TAG").contains(field))
                    return new Wire(
                            "org.stackanvil.packetforge.commons.NbtTag",
                            "NbtCodecs." + field,
                            "NBT");
                if (Set.of("COMPOUND_TAG", "TRUSTED_COMPOUND_TAG").contains(field))
                    return new Wire(
                            "org.stackanvil.packetforge.commons.NbtTag.Compound",
                            field.equals("COMPOUND_TAG")
                                    ? "NbtCodecs.COMPOUND"
                                    : "NbtCodecs.compound(Integer.MAX_VALUE, 512, false)",
                            "NBT_COMPOUND");
                if (field.equals("OPTIONAL_COMPOUND_TAG"))
                    return new Wire(
                            "java.util.Optional<org.stackanvil.packetforge.commons.NbtTag.Compound>",
                            "NbtCodecs.OPTIONAL_COMPOUND",
                            "optional<NBT_COMPOUND>");
                if (field.equals("OPTIONAL_VAR_INT"))
                    return new Wire(
                            "java.util.OptionalInt",
                            "WireCodecs.OPTIONAL_VAR_INT",
                            "OPTIONAL_VAR_INT");
                if (field.equals("PUBLIC_KEY"))
                    return new Wire(
                            "java.security.PublicKey", "CryptoCodecs.PUBLIC_KEY", "PUBLIC_KEY");
                if (field.equals("GAME_PROFILE_PROPERTIES"))
                    return new Wire(
                            "java.util.List<org.stackanvil.packetforge.commons.ProfileProperty>",
                            "WireCodecs.list(ProfileProperty.CODEC, 16)",
                            "list<PROFILE_PROPERTY>");
                if (field.equals("LONG_ARRAY")) return scalar("LongArray");
                if (field.equals("RGB_COLOR"))
                    return new Wire(
                            "int",
                            "WireCodecs.of(b -> 0xff000000 | b.readUnsignedMedium(), (b, v) -> b.writeMedium(v))",
                            "RGB_COLOR");
                if (field.equals("ROTATION_BYTE"))
                    return new Wire("float", "VectorCodecs.ROTATION_BYTE", "ROTATION_BYTE");
                if (field.equals("VECTOR3F"))
                    return new Wire(
                            "org.stackanvil.packetforge.commons.FloatVector3",
                            "VectorCodecs.FLOAT",
                            "VECTOR3F");
                if (field.equals("QUATERNIONF"))
                    return new Wire(
                            "org.stackanvil.packetforge.commons.Quaternion",
                            "VectorCodecs.QUATERNION",
                            "QUATERNIONF");
                if (field.equals("BIT_SET"))
                    return new Wire("java.util.BitSet", "WireCodecs.BIT_SET", "BIT_SET");
                if (field.equals("STRING_UTF8")) return string("32767");
                if (field.equals("BYTE_ARRAY"))
                    return new Wire(
                            "byte[]", "WireCodecs.byteArray(Integer.MAX_VALUE)", "byteArray");
            }
            if (owner.equals("net.minecraft.resources.Identifier") && field.equals("STREAM_CODEC"))
                return scalar("Identifier");
            if (owner.equals("net.minecraft.core.UUIDUtil") && field.equals("STREAM_CODEC"))
                return scalar("UUID");
            if (owner.equals("net.minecraft.core.BlockPos") && field.equals("STREAM_CODEC"))
                return scalar("BlockPos");
            if (owner.equals("net.minecraft.world.phys.Vec3")
                    && Set.of("STREAM_CODEC", "LP_STREAM_CODEC").contains(field))
                return new Wire(
                        "org.stackanvil.packetforge.commons.Vector3",
                        "VectorCodecs."
                                + (field.equals("STREAM_CODEC") ? "DOUBLE" : "LOW_PRECISION"),
                        field.equals("STREAM_CODEC") ? "VECTOR3" : "LP_VECTOR3");
            if (owner.equals("net.minecraft.world.level.ChunkPos") && field.equals("STREAM_CODEC"))
                return scalar("ChunkPos");
            if (owner.equals("net.minecraft.resources.ResourceKey")
                    && field.equals("REGISTRY_STREAM_CODEC")) return scalar("Identifier");
            if (owner.equals("net.minecraft.core.component.DataComponentType")
                    && field.equals("STREAM_CODEC"))
                return new Wire(
                        "int", "WireCodecs.VAR_INT", "REGISTRY:Registries.DATA_COMPONENT_TYPE");
            if (owner.equals("net.minecraft.core.component.TypedDataComponent")
                    && field.equals("STREAM_CODEC")) return componentTable();
            if (owner.equals("net.minecraft.core.component.DataComponentPatch")
                    && Set.of("STREAM_CODEC", "DELIMITED_STREAM_CODEC").contains(field)) {
                Wire table = componentTable();
                String name = table.shape().substring("dispatch:".length());
                return new Wire(
                        "org.stackanvil.packetforge.commons.ComponentPatch",
                        "WireCodecs.lazy(() -> ComponentCodecs.patch(org.stackanvil.packetforge.protocol.GeneratedWireCodecs."
                                + name
                                + "_payloads(), "
                                + field.equals("DELIMITED_STREAM_CODEC")
                                + "))",
                        "COMPONENT_PATCH:" + name);
            }
            if (owner.equals("net.minecraft.world.item.ItemStack")
                    && Set.of(
                                    "STREAM_CODEC",
                                    "OPTIONAL_STREAM_CODEC",
                                    "OPTIONAL_UNTRUSTED_STREAM_CODEC")
                            .contains(field)) {
                Wire patch =
                        codec(
                                new FieldAccessExpr(
                                        new NameExpr(
                                                "net.minecraft.core.component.DataComponentPatch"),
                                        field.equals("OPTIONAL_UNTRUSTED_STREAM_CODEC")
                                                ? "DELIMITED_STREAM_CODEC"
                                                : "STREAM_CODEC"),
                                source);
                return new Wire(
                        "org.stackanvil.packetforge.commons.ItemStack",
                        "ComponentCodecs.itemStack("
                                + patch.codec()
                                + ", "
                                + !field.equals("STREAM_CODEC")
                                + ")",
                        "ITEM_STACK(" + field + ")<" + patch.shape() + ">");
            }
            if (owner.equals("net.minecraft.network.syncher.EntityDataSerializers")) {
                if (field.equals("OPTIONAL_BLOCK_STATE_CODEC"))
                    return new Wire("int", "WireCodecs.VAR_INT", "OPTIONAL_BLOCK_STATE_ID");
                if (field.equals("OPTIONAL_UNSIGNED_INT_CODEC"))
                    return new Wire(
                            "java.util.OptionalInt",
                            "WireCodecs.OPTIONAL_VAR_INT",
                            "OPTIONAL_VAR_INT");
            }
            var target = index.source(owner);
            if (field.equals("STREAM_CODEC")
                    && !(target.declaration() instanceof EnumDeclaration)) {
                var optional =
                        target.declaration()
                                .getFieldByName(field)
                                .flatMap(f -> f.getVariable(0).getInitializer())
                                .orElse(null);
                if (optional instanceof MethodCallExpr call && call.getNameAsString().equals("of"))
                    try {
                        return new SentinelOptionalAnalyzer(this).lower(call, target);
                    } catch (Unsupported notSentinel) {
                    }
                String alias = owner + "#stream-alias";
                if (active.add(alias)) {
                    try {
                        return codec(
                                target.declaration()
                                        .getFieldByName(field)
                                        .orElseThrow(() -> new Unsupported("Missing stream codec"))
                                        .getVariable(0)
                                        .getInitializer()
                                        .orElseThrow(),
                                target);
                    } catch (Unsupported ignored) {
                    } finally {
                        active.remove(alias);
                    }
                }
                Model nested = model(owner);
                return new Wire(
                        nested.generatedName(),
                        nested.generatedName() + ".CODEC",
                        "model:" + owner);
            }
            Expression initializer =
                    target.declaration()
                            .getFieldByName(field)
                            .orElseThrow(
                                    () -> new Unsupported("Missing codec field: " + expression))
                            .getVariable(0)
                            .getInitializer()
                            .orElseThrow();
            String key = owner + "." + field;
            if (!active.add(key)) throw new Unsupported("Recursive codec alias: " + key);
            try {
                return codec(initializer, target);
            } finally {
                active.remove(key);
            }
        }
        if (expression instanceof NameExpr name)
            return codec(
                    new FieldAccessExpr(
                            new NameExpr(source.declaration().getNameAsString()),
                            name.getNameAsString()),
                    source);
        if (expression instanceof MethodCallExpr unscoped
                && unscoped.getScope().isEmpty()
                && source.name().equals("net.minecraft.network.codec.ByteBufCodecs"))
            return codec(unscoped.clone().setScope(new NameExpr("ByteBufCodecs")), source);
        if (expression instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String owner = call.getScope().get().toString();
            String method = call.getNameAsString();
            if (owner.equals("CustomPacketPayload")
                    && method.equals("codec")
                    && call.getArguments().size() == 2
                    && call.getArgument(0) instanceof LambdaExpr)
                return new CustomPayloadAnalyzer(this).lower(call, source);
            if (owner.equals("ByteBufCodecs")) {
                if (Set.of(
                                        "fromCodec",
                                        "fromCodecTrusted",
                                        "fromCodecWithRegistries",
                                        "fromCodecWithRegistriesTrusted")
                                .contains(method)
                        && !call.getArguments().isEmpty())
                    return new Wire(
                            "org.stackanvil.packetforge.commons.NbtTag",
                            method.contains("Trusted") ? "NbtCodecs.TRUSTED_TAG" : "NbtCodecs.TAG",
                            "NBT:" + source.name() + "#" + call.getArgument(0));
                if (method.equals("optionalTagCodec") && call.getArguments().size() == 1) {
                    String quota = "2097152", depth = "512";
                    if (call.getArgument(0) instanceof LambdaExpr lambda
                            && lambda.getBody() instanceof ExpressionStmt body
                            && body.getExpression() instanceof ObjectCreationExpr accounter
                            && accounter.getTypeAsString().equals("NbtAccounter")
                            && accounter.getArguments().size() == 2) {
                        quota = integer(accounter.getArgument(0), source);
                        depth = integer(accounter.getArgument(1), source);
                    }
                    return new Wire(
                            "java.util.Optional<org.stackanvil.packetforge.commons.NbtTag>",
                            "NbtCodecs.optionalTag(" + quota + ", " + depth + ")",
                            "optional<NBT>");
                }
                if (method.equals("fixedBitSet") && call.getArguments().size() == 1) {
                    String bits = integer(call.getArgument(0), source);
                    return new Wire(
                            "java.util.BitSet",
                            "WireCodecs.fixedBitSet(" + bits + ")",
                            "FIXED_BITS(" + bits + ")");
                }
                if (method.equals("lenientJson") && call.getArguments().size() == 1)
                    return new Wire(
                            "com.google.gson.JsonElement",
                            "JsonCodecs.json(" + integer(call.getArgument(0), source) + ")",
                            "JSON");
                if (method.equals("idMapper") && call.getArguments().size() == 1)
                    return scalar("VarInt");
                if (method.equals("idMapper") && call.getArguments().size() == 2)
                    return source.declaration() instanceof EnumDeclaration
                            ? enumIdMapper(call, source)
                            : scalar("VarInt");
                if (Set.of("registry", "holderRegistry").contains(method)
                        && call.getArguments().size() == 1)
                    return new Wire("int", "WireCodecs.VAR_INT", "REGISTRY:" + call.getArgument(0));
                if (method.equals("holderSet") && call.getArguments().size() == 1)
                    return new Wire(
                            "org.stackanvil.packetforge.commons.HolderSet",
                            "WireCodecs.HOLDER_SET",
                            "HOLDER_SET:" + call.getArgument(0));
                if (method.equals("holder") && call.getArguments().size() == 2) {
                    Wire direct = codec(call.getArgument(1), source);
                    return new Wire(
                            "org.stackanvil.packetforge.commons.RegistryValue<"
                                    + boxed(direct.type())
                                    + ">",
                            "WireCodecs.registryValue(" + direct.codec() + ")",
                            "holder(" + call.getArgument(0) + ")<" + direct.shape() + ">");
                }
                if (method.equals("stringUtf8") && call.getArguments().size() == 1)
                    return string(integer(call.getArgument(0), source));
                if (method.equals("byteArray") && call.getArguments().size() == 1)
                    return new Wire(
                            "byte[]",
                            "WireCodecs.byteArray(" + integer(call.getArgument(0), source) + ")",
                            "byteArray");
                if (method.equals("optional") && call.getArguments().size() == 1)
                    return container(
                            "optional", codec(call.getArgument(0), source), "Integer.MAX_VALUE");
                if (method.equals("collection")
                        && (call.getArguments().size() == 2 || call.getArguments().size() == 3)
                        && call.getArgument(0) instanceof MethodReferenceExpr factory)
                    return container(
                            factory.getScope().toString().contains("Set") ? "set" : "list",
                            codec(call.getArgument(1), source),
                            call.getArguments().size() == 3
                                    ? integer(call.getArgument(2), source)
                                    : "Integer.MAX_VALUE");
                if (method.equals("map")
                        && (call.getArguments().size() == 3 || call.getArguments().size() == 4)) {
                    Wire key = codec(call.getArgument(1), source),
                            value = codec(call.getArgument(2), source);
                    return new Wire(
                            "java.util.Map<" + boxed(key.type()) + ", " + boxed(value.type()) + ">",
                            "WireCodecs.map("
                                    + key.codec()
                                    + ", "
                                    + value.codec()
                                    + ", "
                                    + (call.getArguments().size() == 4
                                            ? integer(call.getArgument(3), source)
                                            : "Integer.MAX_VALUE")
                                    + ")",
                            "map<" + key.shape() + "," + value.shape() + ">");
                }
                if (method.equals("either") && call.getArguments().size() == 2) {
                    Wire left = codec(call.getArgument(0), source),
                            right = codec(call.getArgument(1), source);
                    return new Wire(
                            "org.stackanvil.packetforge.commons.Either<"
                                    + boxed(left.type())
                                    + ", "
                                    + boxed(right.type())
                                    + ">",
                            "WireCodecs.either(" + left.codec() + ", " + right.codec() + ")",
                            "either<" + left.shape() + "," + right.shape() + ">");
                }
            }
            if (method.equals("cast") && call.getArguments().isEmpty())
                return codec(call.getScope().orElseThrow(), source);
            if (owner.equals("StreamCodec")
                    && method.equals("recursive")
                    && call.getArguments().size() == 1
                    && call.getArgument(0) instanceof LambdaExpr lambda
                    && lambda.getParameters().size() == 1
                    && lambda.getBody() instanceof ExpressionStmt statement) {
                String generated = index.generated(source.name());
                Wire reference =
                        new Wire(
                                generated,
                                "WireCodecs.lazy(() -> " + generated + ".CODEC)",
                                "model:" + source.name());
                return codec(
                        substitute(
                                statement.getExpression(),
                                Map.of(lambda.getParameter(0).getNameAsString(), bind(reference))),
                        source);
            }
            if (owner.equals("ItemStack")
                    && method.equals("validatedStreamCodec")
                    && call.getArguments().size() == 1) {
                Wire inner = codec(call.getArgument(0), source);
                var item = index.source(index.resolve(owner, source));
                var mapCodec =
                        item.declaration()
                                .getFieldByName("MAP_CODEC")
                                .flatMap(f -> f.getVariable(0).getInitializer())
                                .orElseThrow();
                var bounds =
                        mapCodec.findAll(MethodCallExpr.class).stream()
                                .filter(
                                        c ->
                                                c.getNameAsString().equals("intRange")
                                                        && c.getArguments().size() == 2
                                                        && c.getParentNode().orElse(null)
                                                                instanceof MethodCallExpr parent
                                                        && parent.getNameAsString()
                                                                .equals(
                                                                        "optionalAlwaysPresentFieldOf")
                                                        && parent.getArguments().size() == 3
                                                        && parent.getArgument(1)
                                                                .toString()
                                                                .equals("\"count\""))
                                .toList();
                if (bounds.size() != 1)
                    throw new Unsupported("Item validation has no unambiguous count range");
                return new Wire(
                        inner.type(),
                        "ComponentCodecs.validatedCount("
                                + inner.codec()
                                + ", "
                                + integer(bounds.getFirst().getArgument(0), item)
                                + ", "
                                + integer(bounds.getFirst().getArgument(1), item)
                                + ")",
                        inner.shape(),
                        item.name() + "#validatedStreamCodec:CODEC");
            }
            if (owner.equals("StreamCodec")
                    && method.equals("unit")
                    && call.getArguments().size() == 1)
                return new Wire(
                        "org.stackanvil.packetforge.commons.Unit", "WireCodecs.UNIT", "UNIT");
            if (method.equals("dispatch") && call.getArguments().size() == 2)
                return new DispatchAnalyzer(this).lower(call, source);
            if (owner.equals("StreamCodec") && method.equals("composite"))
                return compositeWire(call, source);
            if (owner.equals("ResourceKey")
                    && method.equals("streamCodec")
                    && call.getArguments().size() == 1) return scalar("Identifier");
            if (method.equals("map") && call.getArguments().size() == 2) {
                String decode = call.getArgument(0).toString(),
                        encode = call.getArgument(1).toString();
                Wire inner = codec(call.getScope().get(), source);
                if (decode.equals("Instant::ofEpochMilli")
                        && encode.equals("Instant::toEpochMilli")
                        && inner.shape().equals("LONG"))
                    return new Wire("java.time.Instant", "WireCodecs.INSTANT", "INSTANT");
                if (decode.equals("Short::intValue")
                        && encode.equals("Integer::shortValue")
                        && inner.shape().equals("SHORT"))
                    return new Wire(
                            "int",
                            "WireCodecs.mapped(WireCodecs.SHORT, Short::intValue, Integer::shortValue)",
                            "SHORT_AS_INT");
                if (decode.equals("ResourceKey::createRegistryKey")
                        && encode.equals("ResourceKey::identifier")
                        && inner.shape().equals("IDENTIFIER")) return inner;
                if (call.getArgument(0) instanceof MethodReferenceExpr factory
                        && factory.getIdentifier().equals("new"))
                    try {
                        String targetName = index.resolve(factory.getScope().toString(), source);
                        var target = index.source(targetName);
                        String field = getterField(call.getArgument(1), target);
                        String targetKey = codecVariants.getOrDefault(targetName, targetName);
                        String targetGenerated =
                                index.generated(targetName)
                                        + (targetKey.equals(targetName)
                                                ? ""
                                                : "_"
                                                        + targetKey.substring(
                                                                targetName.length() + 1));
                        Model projected =
                                new Model(
                                        targetKey,
                                        targetGenerated,
                                        List.of(
                                                new Field(
                                                        field,
                                                        declaredType(field, target),
                                                        inner,
                                                        inner)),
                                        List.of(),
                                        false,
                                        List.of(),
                                        null,
                                        factory.toString());
                        Model existing = models.get(targetKey);
                        if (existing != null && !existing.fields().equals(projected.fields()))
                            throw new Unsupported(
                                    "Conflicting mapped wire projections for " + targetName);
                        models.putIfAbsent(targetKey, projected);
                        return modelWire(models.get(targetKey));
                    } catch (Unsupported domainProjection) {
                    }
                // StreamCodec.map changes the domain representation, not the bytes of its base
                // codec.
                return new Wire(
                        inner.type(),
                        inner.codec(),
                        inner.shape(),
                        source.name() + "#map(" + decode + ", " + encode + ")");
            }
            if (method.equals("apply") && call.getArguments().size() == 1) {
                Wire inner = codec(call.getScope().get(), source);
                Expression argument = call.getArgument(0);
                if (argument instanceof MethodCallExpr length
                        && length.getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("ByteBufCodecs")
                        && Set.of("lengthPrefixed", "registryFriendlyLengthPrefixed")
                                .contains(length.getNameAsString())
                        && length.getArguments().size() == 1) {
                    String max = integer(length.getArgument(0), source);
                    return new Wire(
                            inner.type(),
                            "WireCodecs.lengthPrefixed(" + inner.codec() + ", " + max + ")",
                            "length(" + max + ")<" + inner.shape() + ">");
                }
                if (argument instanceof MethodCallExpr operation
                        && operation
                                .getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("ByteBufCodecs")
                        && operation.getNameAsString().equals("fromCodec")
                        && operation.getArguments().size() == 2)
                    return new Wire(
                            inner.type(),
                            inner.codec(),
                            inner.shape() + ":" + operation.getArgument(1));
                if (argument instanceof MethodCallExpr list
                        && list.getScope().map(Object::toString).orElse("").equals("ByteBufCodecs")
                        && list.getNameAsString().equals("list")
                        && list.getArguments().size() <= 1)
                    return container(
                            "list",
                            inner,
                            list.getArguments().isEmpty()
                                    ? "Integer.MAX_VALUE"
                                    : integer(list.getArgument(0), source));
                if (argument instanceof MethodCallExpr collection
                        && collection
                                .getScope()
                                .map(Object::toString)
                                .orElse("")
                                .equals("ByteBufCodecs")) {
                    if (collection.getNameAsString().equals("fixedSizeList")
                            && collection.getArguments().size() == 1) {
                        String size = integer(collection.getArgument(0), source);
                        return new Wire(
                                "java.util.List<" + boxed(inner.type()) + ">",
                                "WireCodecs.fixedList(" + inner.codec() + ", " + size + ")",
                                "fixedList(" + size + ")<" + inner.shape() + ">");
                    }
                    if (collection.getNameAsString().equals("collection")
                            && (collection.getArguments().size() == 1
                                    || collection.getArguments().size() == 2)
                            && collection.getArgument(0) instanceof MethodReferenceExpr factory)
                        return container(
                                factory.getScope().toString().contains("Set") ? "set" : "list",
                                inner,
                                collection.getArguments().size() == 2
                                        ? integer(collection.getArgument(1), source)
                                        : "Integer.MAX_VALUE");
                }
                if (argument instanceof MethodReferenceExpr ref
                        && ref.getScope().toString().equals("ByteBufCodecs")
                        && ref.getIdentifier().equals("optional"))
                    return container("optional", inner, "Integer.MAX_VALUE");
            }
        }
        if (expression instanceof MethodCallExpr factory && factory.getScope().isPresent()) {
            String identity = "factory:" + source.name() + "#" + expression;
            if (active.add(identity))
                try {
                    return new StaticCodecResolver(this).factory(factory, source);
                } catch (Unsupported unsupported) {
                    throw new Unsupported(
                            "Unsupported stream codec: "
                                    + shortText(expression)
                                    + "; factory: "
                                    + unsupported.getMessage());
                } finally {
                    active.remove(identity);
                }
        }
        throw new Unsupported("Unsupported stream codec: " + shortText(expression));
    }

    private Wire componentTable() {
        var source = index.source("net.minecraft.core.component.TypedDataComponent");
        return new DispatchAnalyzer(this)
                .lower(
                        StaticJavaParser.parseExpression(
                                        "ByteBufCodecs.registry(Registries.DATA_COMPONENT_TYPE).dispatch(TypedDataComponent::type, net.minecraft.core.component.DataComponentType::streamCodec)")
                                .asMethodCallExpr(),
                        source);
    }

    private Wire compositeWire(MethodCallExpr call, SourceIndex.Source source) {
        call = call.clone();
        for (int i = 0; i < call.getArguments().size(); i++) {
            Expression argument = call.getArgument(i);
            while (argument instanceof CastExpr || argument instanceof EnclosedExpr)
                argument =
                        argument instanceof CastExpr cast
                                ? cast.getExpression()
                                : argument.asEnclosedExpr().getInner();
            call.setArgument(i, argument);
        }
        var arguments = call.getArguments();
        if (arguments.isEmpty() || arguments.size() % 2 != 1)
            throw new Unsupported("Invalid composite codec arity");
        Expression factory = arguments.get(arguments.size() - 1);
        String owner;
        if (factory instanceof MethodReferenceExpr reference)
            owner = index.resolve(reference.getScope().toString(), source);
        else if (factory instanceof LambdaExpr) owner = source.name();
        else throw new Unsupported("Composite codec has no constructor function");
        List<Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < arguments.size() - 1; i += 2) {
            Wire wire = codec(arguments.get(i), source);
            Expression getter = arguments.get(i + 1);
            String name;
            if (getter instanceof MethodReferenceExpr reference) name = reference.getIdentifier();
            else if (factory instanceof LambdaExpr lambda
                    && lambda.getParameters().size() == (arguments.size() - 1) / 2)
                name = lambda.getParameter(i / 2).getNameAsString();
            else if (getter instanceof LambdaExpr lambda
                    && lambda.getBody() instanceof ExpressionStmt body
                    && body.getExpression() instanceof FieldAccessExpr field)
                name = field.getNameAsString();
            else name = "field" + i / 2;
            if (name.startsWith("get")
                    && name.length() > 3
                    && Character.isUpperCase(name.charAt(3)))
                name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            if (!names.add(name)) {
                name += "_" + i / 2;
                names.add(name);
            }
            String originalType;
            try {
                originalType =
                        declaredType(getterField(getter, index.source(owner)), index.source(owner));
            } catch (Unsupported ignored) {
                originalType = wire.type();
            }
            fields.add(new Field(name, originalType, wire, wire));
        }
        String generated;
        try {
            generated = index.generated(owner);
        } catch (Unsupported missingExternal) {
            generated = "org.stackanvil.packetforge.protocol.type.external." + owner;
        }
        String modelKey = codecVariants.getOrDefault(owner, owner);
        if (!modelKey.equals(owner)) generated += "_" + modelKey.substring(owner.length() + 1);
        try {
            var declaration = index.source(owner).declaration();
            boolean generic =
                    declaration instanceof ClassOrInterfaceDeclaration c
                                    && !c.getTypeParameters().isEmpty()
                            || declaration instanceof RecordDeclaration r
                                    && !r.getTypeParameters().isEmpty();
            if (generic) {
                String suffix = Integer.toUnsignedString(fields.toString().hashCode());
                modelKey += "#" + suffix;
                generated += "_" + suffix;
            }
        } catch (Unsupported external) {
        }
        Model projection =
                new Model(
                        modelKey,
                        generated,
                        List.copyOf(fields),
                        List.of(),
                        false,
                        recordGuards(owner, fields),
                        null,
                        factory.toString());
        Model existing = models.get(modelKey);
        if (existing != null && !existing.fields().equals(projection.fields()))
            throw new Unsupported("Conflicting wire projections for " + owner);
        models.putIfAbsent(modelKey, projection);
        return modelWire(models.get(modelKey));
    }

    private List<String> recordGuards(String name, List<Field> fields) {
        try {
            return recordGuards(index.source(name), fields);
        } catch (Unsupported externalDomainType) {
            return List.of();
        }
    }

    private List<String> recordGuards(SourceIndex.Source source, List<Field> fields) {
        if (!(source.declaration() instanceof RecordDeclaration record)) return List.of();
        Set<String> names =
                fields.stream().map(Field::name).collect(java.util.stream.Collectors.toSet());
        List<String> guards = new ArrayList<>();
        for (var member : record.getMembers())
            if (member instanceof CompactConstructorDeclaration constructor)
                for (var statement : constructor.getBody().getStatements()) {
                    if (!(statement instanceof IfStmt guard) || guard.getElseStmt().isPresent())
                        continue;
                    Statement body = guard.getThenStmt();
                    if (body instanceof BlockStmt block && block.getStatements().size() == 1)
                        body = block.getStatement(0);
                    if (!(body instanceof ThrowStmt thrown)
                            || !(thrown.getExpression() instanceof ObjectCreationExpr exception)
                            || !exception.getTypeAsString().equals("IllegalArgumentException"))
                        continue;
                    if (guard.findAll(NameExpr.class).stream()
                            .anyMatch(n -> !names.contains(n.getNameAsString()))) continue;
                    if (guard.findAll(FieldAccessExpr.class).stream()
                            .anyMatch(f -> !(f.getScope() instanceof ThisExpr))) continue;
                    boolean unsafe = false;
                    for (var call : guard.findAll(MethodCallExpr.class)) {
                        if (!Set.of("size", "isEmpty").contains(call.getNameAsString())
                                || !call.getArguments().isEmpty()
                                || !(call.getScope().orElse(null) instanceof NameExpr name)) {
                            unsafe = true;
                            break;
                        }
                        var field =
                                fields.stream()
                                        .filter(f -> f.name().equals(name.getNameAsString()))
                                        .findFirst()
                                        .orElse(null);
                        if (field == null || !field.read().type().startsWith("java.util.")) {
                            unsafe = true;
                            break;
                        }
                    }
                    if (!unsafe) guards.add(guard.toString());
                }
        return List.copyOf(guards);
    }

    String integer(Expression expression, SourceIndex.Source source) {
        if (expression instanceof EnclosedExpr enclosed)
            return integer(enclosed.getInner(), source);
        if (expression instanceof BinaryExpr binary) {
            int left = Integer.parseInt(integer(binary.getLeft(), source)),
                    right = Integer.parseInt(integer(binary.getRight(), source));
            return Integer.toString(
                    switch (binary.getOperator()) {
                        case BINARY_OR -> left | right;
                        case BINARY_AND -> left & right;
                        case XOR -> left ^ right;
                        case LEFT_SHIFT -> left << right;
                        case SIGNED_RIGHT_SHIFT -> left >> right;
                        case UNSIGNED_RIGHT_SHIFT -> left >>> right;
                        case PLUS -> left + right;
                        case MINUS -> left - right;
                        case MULTIPLY -> left * right;
                        default ->
                                throw new Unsupported("Unsupported integer operation: " + binary);
                    });
        }
        if (expression instanceof IntegerLiteralExpr literal)
            return Integer.toString(literal.asNumber().intValue());
        if (expression instanceof LongLiteralExpr literal)
            return Integer.toString(Math.toIntExact(literal.asNumber().longValue()));
        if (expression instanceof NameExpr name) {
            Expression initializer =
                    source.declaration()
                            .getFieldByName(name.getNameAsString())
                            .orElseThrow(
                                    () -> new Unsupported("Unresolved integer constant: " + name))
                            .getVariable(0)
                            .getInitializer()
                            .orElseThrow();
            return integer(initializer, source);
        }
        if (expression instanceof FieldAccessExpr access) {
            if (access.toString().equals("Integer.MAX_VALUE")) return "Integer.MAX_VALUE";
            if (access.getScope() instanceof FieldAccessExpr constant) {
                var owner = index.source(index.resolve(constant.getScope().toString(), source));
                if (owner.declaration() instanceof EnumDeclaration enumeration) {
                    var entry =
                            enumeration.getEntries().stream()
                                    .filter(
                                            e ->
                                                    e.getNameAsString()
                                                            .equals(constant.getNameAsString()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new Unsupported(
                                                            "Unknown enum constant: " + constant));
                    var constructors =
                            enumeration.getMembers().stream()
                                    .filter(m -> m instanceof ConstructorDeclaration)
                                    .map(m -> (ConstructorDeclaration) m)
                                    .toList();
                    return integer(
                            enumConstructorField(
                                    constructors,
                                    entry.getArguments(),
                                    access.getNameAsString(),
                                    new HashSet<>()),
                            owner);
                }
            }
            var owner = index.source(index.resolve(access.getScope().toString(), source));
            return integer(
                    owner.declaration()
                            .getFieldByName(access.getNameAsString())
                            .orElseThrow(
                                    () ->
                                            new Unsupported(
                                                    "Unresolved integer constant: " + expression))
                            .getVariable(0)
                            .getInitializer()
                            .orElseThrow(),
                    owner);
        }
        throw new Unsupported("Non-literal codec limit: " + expression);
    }

    private static Wire scalar(String operation) {
        String[] spec = SCALARS.get(operation).split(":", 2);
        return new Wire(spec[1], "WireCodecs." + spec[0], spec[0]);
    }

    private static Wire string(String max) {
        return new Wire("String", "WireCodecs.string(" + max + ")", "UTF8");
    }

    private static Wire container(String kind, Wire inner, String max) {
        String type =
                switch (kind) {
                    case "optional" -> "java.util.Optional<" + boxed(inner.type()) + ">";
                    case "list" -> "java.util.List<" + boxed(inner.type()) + ">";
                    case "set" -> "java.util.Set<" + boxed(inner.type()) + ">";
                    case "nullable" -> boxed(inner.type());
                    default -> throw new Unsupported("Unknown container: " + kind);
                };
        String codec =
                "WireCodecs."
                        + kind
                        + "("
                        + inner.codec()
                        + (Set.of("list", "set").contains(kind) ? ", " + max : "")
                        + ")";
        return new Wire(type, codec, kind + "<" + inner.shape() + ">");
    }

    static String boxed(String type) {
        return switch (type) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type;
        };
    }

    static Wire modelWire(Model model) {
        return new Wire(
                model.generatedName(),
                model.generatedName() + ".CODEC",
                "model:" + model.originalName());
    }

    String getterField(Expression expression, SourceIndex.Source source) {
        if (expression instanceof MethodReferenceExpr getter
                && index.resolve(getter.getScope().toString(), source).equals(source.name())) {
            String name = getter.getIdentifier();
            if (source.declaration() instanceof RecordDeclaration record
                    && source.declaration().getMethodsByName(name).isEmpty()
                    && record.getParameters().stream()
                            .anyMatch(p -> p.getNameAsString().equals(name))) return name;
            var methods = source.declaration().getMethodsByName(name);
            if (methods.size() == 1
                    && methods.getFirst().getBody().isEmpty()
                    && source.declaration() instanceof ClassOrInterfaceDeclaration clazz
                    && clazz.isInterface()) return name;
            if (methods.size() == 1
                    && methods.getFirst().getParameters().isEmpty()
                    && methods.getFirst().getBody().isPresent()) {
                var body = methods.getFirst().getBody().orElseThrow();
                if (body.getStatements().size() == 1
                        && body.getStatement(0) instanceof ReturnStmt returned)
                    return directField(returned.getExpression().orElseThrow());
            }
        }
        if (expression instanceof LambdaExpr lambda
                && lambda.getParameters().size() == 1
                && lambda.getBody() instanceof ExpressionStmt body
                && body.getExpression() instanceof FieldAccessExpr field
                && field.getScope().toString().equals(lambda.getParameter(0).getNameAsString()))
            return field.getNameAsString();
        throw new Unsupported(
                "Composite getter is not a direct field accessor: " + shortText(expression));
    }

    private static String directField(Expression expression) {
        if (expression instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr)
            return field.getNameAsString();
        throw new Unsupported("Expression is not a direct field: " + shortText(expression));
    }

    static String declaredType(String name, SourceIndex.Source source) {
        if (source.declaration() instanceof RecordDeclaration record)
            return record.getParameters().stream()
                    .filter(p -> p.getNameAsString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new Unsupported("Unknown record field: " + name))
                    .getTypeAsString();
        if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz
                && clazz.isInterface()) {
            var methods = clazz.getMethodsByName(name);
            if (methods.size() == 1 && methods.getFirst().getParameters().isEmpty())
                return methods.getFirst().getTypeAsString();
        }
        return source
                .declaration()
                .getFieldByName(name)
                .orElseThrow(() -> new Unsupported("Unknown field: " + name))
                .getVariables()
                .stream()
                .filter(v -> v.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow()
                .getTypeAsString();
    }

    private static boolean isBuffer(String type) {
        return Set.of("FriendlyByteBuf", "RegistryFriendlyByteBuf", "ByteBuf").contains(type);
    }

    private static String shortText(Node node) {
        String text = node.toString().replaceAll("\\s+", " ");
        return text.length() > 180 ? text.substring(0, 177) + "..." : text;
    }
}
