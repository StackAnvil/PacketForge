package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.List;
import static org.stackanvil.packetforge.generator.Analyzer.*;
import static org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Checks the dependent movement-delta layout before using its shared wire primitive. */
final class MovementDeltaAnalyzer {
    private final Analyzer analyzer;
    MovementDeltaAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    Wire value() {
        var source = analyzer.index.source("net.minecraft.network.protocol.game.VecDelta");
        var readers = source.declaration().getMethodsByName("read"); var writers = source.declaration().getMethodsByName("write");
        if (readers.size() != 1 || writers.size() != 1 || readers.getFirst().getParameters().size() != 2 || writers.getFirst().getParameters().size() != 2) throw new Unsupported("Unknown movement-delta entry points");
        var read = readers.getFirst(); var write = writers.getFirst(); String input = read.getParameter(0).getNameAsString(), output = write.getParameter(0).getNameAsString();
        var operations = read.getBody().orElseThrow().findAll(MethodCallExpr.class).stream().filter(c -> c.getScope().map(Object::toString).orElse("").equals(input)).map(MethodCallExpr::getNameAsString).toList();
        var encoded = write.getBody().orElseThrow().findAll(MethodCallExpr.class).stream().filter(c -> c.getScope().map(Object::toString).orElse("").equals(output)).map(MethodCallExpr::getNameAsString).toList();
        if (!operations.equals(List.of("readShort", "readShort", "readShort", "readableBytes", "readVarInt", "readShort", "readShort", "readShort")) || !encoded.equals(List.of("writeShort", "writeShort", "writeShort", "writeVarInt", "writeShort", "writeShort", "writeShort"))) throw new Unsupported("Movement-delta wire operations changed");
        String count = read.getParameter(1).getNameAsString();
        var branches = read.getBody().get().findAll(IfStmt.class); var loops = read.getBody().get().findAll(ForStmt.class);
        if (branches.size() != 2 || !branches.getFirst().getCondition().toString().equals(count + " <= 0") || branches.getFirst().getElseStmt().isPresent() || loops.size() != 1) throw new Unsupported("Movement-delta count branches changed");
        var loop = loops.getFirst();
        if (!loop.getCompare().map(Object::toString).orElse("").equals("i < " + count) || !loop.getInitialization().toString().equals("[int i = 0]") || !loop.getUpdate().toString().equals("[i++]") || !loop.getBody().findAll(IfStmt.class).isEmpty() || !loop.getBody().findAll(BreakStmt.class).isEmpty() || !loop.getBody().findAll(ContinueStmt.class).isEmpty()) throw new Unsupported("Movement-delta step traversal changed");
        var writeArguments = write.getBody().get().findAll(MethodCallExpr.class).stream().filter(c -> c.getScope().map(Object::toString).orElse("").equals(output)).map(c -> c.getArguments().toString()).toList();
        if (!writeArguments.equals(List.of("[xa]", "[ya]", "[za]", "[step.ticks]", "[step.xa]", "[step.ya]", "[step.za]"))) throw new Unsupported("Movement-delta component order changed");
        if (read.getBody().get().findAll(BinaryExpr.class).stream().noneMatch(b -> b.getOperator() == BinaryExpr.Operator.DIVIDE && b.getLeft().toString().equals(input + ".readableBytes()") && b.getRight().toString().equals("7"))) throw new Unsupported("Movement-delta input bound changed");
        var linear = analyzer.index.source(source.name() + ".Linear").declaration(); var step = analyzer.index.source(source.name() + ".Stepped.DeltaStep").declaration();
        if (!(linear instanceof RecordDeclaration a) || !(step instanceof RecordDeclaration b) || !a.getParameters().stream().map(p -> p.getTypeAsString()).toList().equals(List.of("short", "short", "short")) || !b.getParameters().stream().map(p -> p.getTypeAsString()).toList().equals(List.of("short", "short", "short", "int"))) throw new Unsupported("Movement-delta value records changed");
        return new Wire("org.stackanvil.packetforge.commons.MovementDelta", "", "program-dependent:MOVEMENT_DELTA", source.name() + "#read/write");
    }
}
