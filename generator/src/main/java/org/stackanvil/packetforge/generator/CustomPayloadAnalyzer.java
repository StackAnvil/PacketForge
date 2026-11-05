package org.stackanvil.packetforge.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Recovers declared channel codecs and the bounded remaining-byte fallback. */
final class CustomPayloadAnalyzer {
    private final Analyzer analyzer;
    CustomPayloadAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    Wire lower(MethodCallExpr call, SourceIndex.Source source) {
        if (call.getArguments().size() != 2 || !(call.getArgument(0) instanceof LambdaExpr fallback) || fallback.getParameters().size() != 1 || !(fallback.getBody() instanceof ExpressionStmt body) || !(body.getExpression() instanceof MethodCallExpr codec) || !codec.getNameAsString().equals("codec") || !codec.getScope().map(s -> analyzer.index.resolve(s.toString(), source)).orElse("").equals("net.minecraft.network.protocol.common.custom.DiscardedPayload") || codec.getArguments().size() != 2 || !codec.getArgument(0).toString().equals(fallback.getParameter(0).getNameAsString())) throw new Unsupported("Custom payload fallback is not a bounded discarded payload");
        String limit = analyzer.integer(codec.getArgument(1), source);
        Expression declared = call.getArgument(1);
        if (declared instanceof MethodCallExpr make && make.getScope().map(Object::toString).orElse("").equals("Util") && make.getNameAsString().equals("make") && make.getArguments().size() == 2) {
            if (!(make.getArgument(1) instanceof LambdaExpr initializer) || !(initializer.getBody() instanceof BlockStmt block) || !block.getStatements().isEmpty()) throw new Unsupported("Custom payload table initializer changes its entries");
            declared = make.getArgument(0);
        }
        List<Expression> entries;
        if (declared instanceof MethodCallExpr list && list.getScope().map(Object::toString).orElse("").equals("List") && list.getNameAsString().equals("of")) entries = List.copyOf(list.getArguments());
        else if (declared instanceof MethodCallExpr list && list.getScope().map(Object::toString).orElse("").equals("Lists") && list.getNameAsString().equals("newArrayList") && list.getArguments().size() == 1 && list.getArgument(0) instanceof ArrayCreationExpr array && array.getInitializer().isPresent()) entries = List.copyOf(array.getInitializer().get().getValues());
        else throw new Unsupported("Custom payload table is not a static list");
        var resolver = new StaticCodecResolver(analyzer); List<DispatchCase> cases = new ArrayList<>(); Set<String> channels = new HashSet<>();
        for (var entry : entries) {
            if (!(entry instanceof ObjectCreationExpr pair) || !pair.getType().getNameAsString().equals("TypeAndCodec") || pair.getArguments().size() != 2) throw new Unsupported("Custom payload entry is not a type-codec pair");
            var type = resolver.resolve(new StaticCodecResolver.Value(pair.getArgument(0), source));
            if (type.expression() instanceof MethodCallExpr factory && factory.getScope().isPresent()) {
                var reference = StaticJavaParser.parseExpression(factory.getScope().get() + "::" + factory.getNameAsString());
                type = resolver.invoke(new StaticCodecResolver.Value(reference, type.source()), factory.getArguments().stream().map(type::child).toList());
            }
            String id = resolver.identifier(resolver.accessor(type, "id"));
            if (!channels.add(id)) throw new Unsupported("Repeated custom payload channel: " + id);
            cases.add(new DispatchCase(id, analyzer.codec(pair.getArgument(1), source), id));
        }
        String sourceKey = analyzer.codecVariants.getOrDefault(source.name(), source.name());
        String name = "dispatch_" + Integer.toUnsignedString((sourceKey + "#custom:" + limit + "#" + cases).hashCode());
        Wire key = new Wire("String", "WireCodecs.IDENTIFIER", "IDENTIFIER");
        Wire fallbackWire = new Wire("byte[]", "WireCodecs.remainingBytes(" + limit + ")", "remainingBytes(" + limit + ")", "net.minecraft.network.protocol.common.custom.DiscardedPayload#codec");
        String entriesCode = String.join(", ", cases.stream().map(c -> "java.util.Map.entry(" + new com.google.gson.Gson().toJson(c.key()) + ", (PacketCodec<?>) " + c.value().codec() + ")").toList());
        Wire implementation = new Wire("org.stackanvil.packetforge.commons.Variant<String>", "WireCodecs.dispatchWithFallback(WireCodecs.IDENTIFIER, java.util.Map.<String, PacketCodec<?>>ofEntries(" + entriesCode + "), " + fallbackWire.codec() + ")", "dispatch:" + name);
        String field = sourceKey.contains("#") ? sourceKey.substring(sourceKey.indexOf('#') + 1) : "STREAM_CODEC";
        analyzer.dispatches.put(name, new Dispatch(name, key, List.copyOf(cases), source.name(), call.toString(), implementation, "", field, fallbackWire));
        return new Wire(implementation.type(), "org.stackanvil.packetforge.protocol.GeneratedWireCodecs." + name, implementation.shape());
    }
}
