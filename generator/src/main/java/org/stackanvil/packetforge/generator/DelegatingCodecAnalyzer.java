package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Recovers codecs that delegate decoding and encoding to different wire schemas. */
final class DelegatingCodecAnalyzer {
    private final Analyzer analyzer;
    DelegatingCodecAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    record Lowered(List<Field> fields, String decodeFailure) {}
    Lowered lower(ObjectCreationExpr codec, SourceIndex.Source source) {
        var members = codec.getAnonymousClassBody().orElseThrow();
        var readers = members.stream().filter(m -> m instanceof MethodDeclaration d && d.getNameAsString().equals("decode")).map(m -> (MethodDeclaration) m).toList();
        var writers = members.stream().filter(m -> m instanceof MethodDeclaration d && d.getNameAsString().equals("encode")).map(m -> (MethodDeclaration) m).toList();
        if (readers.size() != 1 || writers.size() != 1) throw new Unsupported("Not a forwarding stream codec");
        var reader = readers.getFirst(); var writer = writers.getFirst();
        String decodeFailure = "";
        BlockStmt readBody = reader.getBody().orElseThrow(), writeBody = writer.getBody().orElseThrow();
        if (readBody.getStatements().size() == 1 && readBody.getStatement(0) instanceof TryStmt attempt && attempt.getFinallyBlock().isEmpty() && attempt.getResources().isEmpty()) {
            for (var handler : attempt.getCatchClauses()) if (handler.getBody().getStatements().size() != 1 || !(handler.getBody().getStatement(0) instanceof ThrowStmt thrown) || !(thrown.getExpression() instanceof ObjectCreationExpr exception) || exception.getArguments().size() != 1 || !exception.getArgument(0).toString().equals(handler.getParameter().getNameAsString())) throw new Unsupported("Codec exception handler changes decoded values");
            if (attempt.getCatchClauses().size() != 1 || !attempt.getCatchClauses().get(0).getParameter().getTypeAsString().equals("DecoderException") || !attempt.getCatchClauses().get(0).getBody().findAll(ObjectCreationExpr.class).getFirst().getTypeAsString().equals("SkipPacketDecoderException")) throw new Unsupported("Unknown forwarding codec exception policy");
            decodeFailure = "SkipPacketDecoderException";
            readBody = attempt.getTryBlock();
        }
        if (readBody.getStatements().size() != 1 || !(readBody.getStatement(0) instanceof ReturnStmt returned) || !(returned.getExpression().orElse(null) instanceof MethodCallExpr decode) || !decode.getNameAsString().equals("decode") || decode.getArguments().size() != 1 || !decode.getArgument(0).toString().equals(reader.getParameter(0).getNameAsString()) || writeBody.getStatements().size() != 1 || !(writeBody.getStatement(0) instanceof ExpressionStmt expression) || !(expression.getExpression() instanceof MethodCallExpr encode) || !encode.getNameAsString().equals("encode") || encode.getArguments().size() != 2 || !encode.getArgument(0).toString().equals(writer.getParameter(0).getNameAsString()) || !encode.getArgument(1).toString().equals(writer.getParameter(1).getNameAsString())) throw new Unsupported("Codec has operations outside its delegate");
        var resolver = new StaticCodecResolver(analyzer);
        var local = List.copyOf(members);
        Wire read = resolver.codec(new StaticCodecResolver.Value(decode.getScope().orElseThrow(), source, Map.of(), local));
        Wire write = resolver.codec(new StaticCodecResolver.Value(encode.getScope().orElseThrow(), source, Map.of(), local));
        if (!read.shape().startsWith("model:") || !write.shape().startsWith("model:")) throw new Unsupported("Forwarding codecs do not construct record layouts");
        var readModel = analyzer.models.get(read.shape().substring(6)); var writeModel = analyzer.models.get(write.shape().substring(6));
        if (readModel.fields().size() != writeModel.fields().size()) throw new Unsupported("Forwarding record layouts have different arities");
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < readModel.fields().size(); i++) {
            var a = readModel.fields().get(i); var b = writeModel.fields().get(i);
            if (!a.name().equals(b.name()) || !a.read().type().equals(b.write().type())) throw new Unsupported("Forwarding codec fields have different types");
            fields.add(new Field(a.name(), a.originalType(), a.read(), b.write()));
        }
        return new Lowered(List.copyOf(fields), decodeFailure);
    }
}
