package org.stackanvil.packetforge.generator;

import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.stackanvil.packetforge.generator.Analyzer.Field;
import org.stackanvil.packetforge.generator.Analyzer.Wire;
import org.stackanvil.packetforge.generator.ExtractionAttempt.Matched;
import org.stackanvil.packetforge.generator.ExtractionAttempt.NotApplicable;
import org.stackanvil.packetforge.generator.ExtractionAttempt.Rejected;

/** Selects serialization rules independently of model identity, caching, and reachability. */
final class ModelExtractor {
    private final Analyzer analyzer;

    ModelExtractor(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    ModelLayout extract(Expression codec, SourceIndex.Source source, String generatedName) {
        ExtractionAttempt result = specialized(codec, source, generatedName);
        if (result == NotApplicable.INSTANCE) result = general(codec, source, generatedName);
        return switch (result) {
            case Matched matched -> matched.layout();
            case Rejected rejected -> throw new Unsupported(rejected.reason());
            case NotApplicable ignored -> throw new IllegalStateException("No final extraction result");
        };
    }

    private ExtractionAttempt specialized(Expression codec, SourceIndex.Source source, String generatedName) {
        return switch (source.name()) {
            case "net.minecraft.network.protocol.game.ClientboundCommandsPacket" ->
                attempt(() -> {
                    var lowered = new CommandPacketAnalyzer(analyzer).lower(codec, source);
                    return new ModelLayout(lowered.fields(), null, lowered.validations(), "");
                });
            case "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket" ->
                attempt(() -> ModelLayout.program(new ActionSetAnalyzer(analyzer).lower(codec, source, generatedName)));
            case "net.minecraft.network.protocol.login.ClientboundCustomQueryPacket",
                    "net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket" ->
                attempt(() -> ModelLayout.fields(new LoginQueryAnalyzer(analyzer).lower(codec, source)));
            default -> NotApplicable.INSTANCE;
        };
    }

    private ExtractionAttempt general(Expression codec, SourceIndex.Source source, String generatedName) {
        ExtractionAttempt direct = declarative(codec, source);
        if (direct instanceof Matched) return direct;
        if (isStreamFactory(codec, "composite")) {
            return attempt(() -> fieldsFromModel(analyzer.compositeWire((MethodCallExpr) codec, source)));
        }

        var enumOperations = new EnumOperationAnalyzer(analyzer);
        ExtractionAttempt operations = enumOperations.accepts(codec, source)
                ? attempt(() -> ModelLayout.fields(enumOperations.lower(codec, source)))
                : NotApplicable.INSTANCE;
        if (operations instanceof Matched) return operations;

        ExtractionAttempt imperative = imperative(codec, source, generatedName);
        if (imperative instanceof Matched) return imperative;

        ExtractionAttempt projection =
                attempt(() -> ModelLayout.fields(new LinearWireProjection(analyzer).lower(codec, source)));
        if (projection instanceof Matched) return projection;

        String original = direct instanceof Rejected rejected
                ? rejected.reason()
                : "Unsupported codec declaration: " + Analyzer.shortText(codec);
        if (imperative instanceof Rejected rejected && projection instanceof Rejected projected) {
            return new Rejected(
                    original + "; imperative: " + rejected.reason() + "; projection: " + projected.reason());
        }
        return new Rejected(original);
    }

    private ExtractionAttempt declarative(Expression codec, SourceIndex.Source source) {
        if (isStreamFactory(codec, "unit")) return new Matched(ModelLayout.fields(List.of()));
        if (isStreamFactory(codec, "composite")) {
            return attempt(() -> ModelLayout.fields(analyzer.composite((MethodCallExpr) codec, source)));
        }
        if (codec instanceof MethodCallExpr call) {
            if (call.getScope().orElse(null) instanceof NameExpr scope
                    && Set.of("Packet", "StreamCodec", "CustomPacketPayload").contains(scope.getNameAsString())
                    && Set.of("codec", "ofMember").contains(call.getNameAsString())) {
                return attempt(() -> ModelLayout.fields(analyzer.memberCodec(call, source)));
            }
            if (isConstructorMapping(call, source)) {
                return attempt(() -> {
                    String field = analyzer.getterField(call.getArgument(1), source);
                    Wire underlying = analyzer.codec(call.getScope().orElseThrow(), source);
                    return ModelLayout.fields(
                            List.of(new Field(field, Analyzer.declaredType(field, source), underlying, underlying)));
                });
            }
            if (call.getScope().isEmpty()) {
                return attempt(() -> {
                    Wire underlying = new StaticCodecResolver(analyzer)
                            .factory(
                                    call.clone()
                                            .setScope(new NameExpr(
                                                    source.declaration().getNameAsString())),
                                    source);
                    if (!underlying.shape().startsWith("model:")) {
                        throw new Unsupported("Codec factory does not construct its declared model");
                    }
                    return fieldsFromModel(underlying);
                });
            }
        }
        if (codec instanceof NameExpr || codec instanceof FieldAccessExpr) {
            return attempt(() -> {
                Wire underlying = analyzer.codec(codec, source);
                if (!underlying.shape().startsWith("model:")) {
                    throw new Unsupported("Codec alias does not construct this value");
                }
                return fieldsFromModel(underlying);
            });
        }
        if (codec instanceof ObjectCreationExpr anonymous
                && anonymous.getAnonymousClassBody().isPresent()) {
            return attempt(() -> {
                var lowered = new DelegatingCodecAnalyzer(analyzer).lower(anonymous, source);
                return new ModelLayout(lowered.fields(), null, List.of(), lowered.decodeFailure());
            });
        }
        return NotApplicable.INSTANCE;
    }

    private ExtractionAttempt imperative(Expression codec, SourceIndex.Source source, String generatedName) {
        if (source.declaration() instanceof ClassOrInterfaceDeclaration clazz && clazz.isAbstract()) {
            return attempt(() -> ModelLayout.fields(new PolymorphicConstructorAnalyzer(analyzer).lower(codec, source)));
        }
        if (!ProgramLowerer.accepts(codec)) return NotApplicable.INSTANCE;
        return attempt(() -> ModelLayout.program(new ProgramLowerer(analyzer, source, generatedName).lower(codec)));
    }

    private ModelLayout fieldsFromModel(Wire wire) {
        return ModelLayout.fields(
                analyzer.models.get(wire.shape().substring("model:".length())).fields());
    }

    private boolean isConstructorMapping(MethodCallExpr call, SourceIndex.Source source) {
        return call.getNameAsString().equals("map")
                && call.getArguments().size() == 2
                && call.getArgument(0) instanceof MethodReferenceExpr constructor
                && constructor.getIdentifier().equals("new")
                && analyzer.index
                        .resolve(constructor.getScope().toString(), source)
                        .equals(source.name());
    }

    private boolean isStreamFactory(Expression codec, String method) {
        return codec instanceof MethodCallExpr call
                && call.getScope().orElse(null) instanceof NameExpr scope
                && scope.getNameAsString().equals("StreamCodec")
                && call.getNameAsString().equals(method);
    }

    private ExtractionAttempt attempt(Supplier<ModelLayout> extraction) {
        try {
            return new Matched(extraction.get());
        } catch (Unsupported failure) {
            return new Rejected(failure.getMessage());
        }
    }
}
