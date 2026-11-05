package org.stackanvil.packetforge.generator;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolymorphicConstructorAnalyzerTest {
    @TempDir Path temporary;
    private static final String SOURCE =
            """
        package net.minecraft.fixture;
        abstract class Base {
            final int token;
            final Base.Kind kind;
            Base(int token, Base.Kind kind) { this.token = token; this.kind = kind; }
            static Base read(ByteBuf raw) {
                FriendlyByteBuf input = new FriendlyByteBuf(raw);
                int token = input.readInt();
                Base.Kind kind = input.readEnum(Base.Kind.class);
                return (Base) kind.constructor.apply(token, input);
            }
            void write(ByteBuf raw) {
                FriendlyByteBuf output = new FriendlyByteBuf(raw);
                output.writeInt(this.token);
                output.writeEnum(this.kind);
                this.writeContents(raw);
            }
            abstract void writeContents(ByteBuf raw);
            enum Kind {
                EMPTY(Base.Empty::new), NUMBER(Base.Number::new);
                final Factory constructor;
                Kind(Factory constructor) { this.constructor = constructor; }
            }
            static class Empty extends Base {
                Empty(int token, FriendlyByteBuf input) { super(token, Base.Kind.EMPTY); }
                void writeContents(ByteBuf output) {}
            }
            static class Number extends Base {
                final int amount;
                Number(int token, FriendlyByteBuf input) { super(token, Base.Kind.NUMBER); this.amount = input.readVarInt(); }
                void writeContents(ByteBuf output) { VarInt.write(output, this.amount); }
            }
        }
        """;

    private Analyzer analyze(String body) throws Exception {
        Path file = temporary.resolve("net/minecraft/fixture/Base.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        var analyzer = new Analyzer(temporary);
        new PolymorphicConstructorAnalyzer(analyzer)
                .lower(
                        StaticJavaParser.parseExpression(
                                "StreamCodec.ofMember(Base::write, Base::read)"),
                        analyzer.index.source("net.minecraft.fixture.Base"));
        return analyzer;
    }

    @Test
    void separatesSharedHeaderAndRecoversEveryConstructorPayload() throws Exception {
        var analyzer = analyze(SOURCE);
        var table = analyzer.dispatches.values().iterator().next();
        assertEquals(
                List.of("EMPTY", "NUMBER"),
                table.cases().stream().map(Analyzer.DispatchCase::key).toList());
        assertEquals("UNIT", table.cases().getFirst().value().shape());
        var number = analyzer.models.get("net.minecraft.fixture.Base.Number#contents");
        assertEquals(
                List.of("amount"), number.fields().stream().map(Analyzer.Field::name).toList());
        assertEquals(List.of("VAR_INT"), number.program().operations());
    }

    @Test
    void rejectsChangedDiscriminatorsHeaderTransformsAndPayloadWireMismatches() {
        for (String body :
                List.of(
                        SOURCE.replace(
                                "super(token, Base.Kind.NUMBER)", "super(token, Base.Kind.EMPTY)"),
                        SOURCE.replace(
                                "super(token, Base.Kind.NUMBER)",
                                "super(token + 1, Base.Kind.NUMBER)"),
                        SOURCE.replace(
                                "this.amount = input.readVarInt()",
                                "this.amount = input.readVarInt() + token"),
                        SOURCE.replace(
                                "VarInt.write(output, this.amount)",
                                "output.writeInt(this.amount)"),
                        SOURCE.replace(
                                "output.writeEnum(this.kind)", "output.writeEnum(Base.Kind.EMPTY)"),
                        SOURCE.replace("this.token = token", "this.token = token + 1"))) {
            assertThrows(SourceIndex.Unsupported.class, () -> analyze(body));
        }
    }
}
