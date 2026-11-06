package org.stackanvil.packetforge.generator;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.Optional;
import org.stackanvil.packetforge.generator.Analyzer.Wire;

/** Recognizes semantic text codecs by source identity before lowering generic NBT operations. */
final class TextWire {
    private TextWire() {}

    static Optional<Wire> nbt(Expression expression, SourceIndex.Source source, SourceIndex index, boolean trusted) {
        if (expression instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed)
            return nbt(enclosed.getInner(), source, index, trusted);
        if (expression instanceof com.github.javaparser.ast.expr.CastExpr cast)
            return nbt(cast.getExpression(), source, index, trusted);
        String owner;
        if (expression instanceof NameExpr name && name.getNameAsString().equals("CODEC")) owner = source.name();
        else if (expression instanceof FieldAccessExpr field
                && field.getNameAsString().equals("CODEC"))
            owner = index.resolve(field.getScope().toString(), source);
        else return Optional.empty();
        return switch (owner) {
            case "net.minecraft.network.chat.ComponentSerialization" -> Optional.of(component(trusted));
            case "net.minecraft.network.chat.Style.Serializer" ->
                Optional.of(new Wire(
                        "net.kyori.adventure.text.format.Style",
                        codecs() + (trusted ? ".TRUSTED_STYLE" : ".STYLE"),
                        "NBT:net.minecraft.network.chat.Style.Serializer#CODEC"));
            default -> Optional.empty();
        };
    }

    static Wire component(boolean trusted) {
        return new Wire(
                "net.kyori.adventure.text.Component",
                codecs() + (trusted ? ".TRUSTED_COMPONENT" : ".COMPONENT"),
                "NBT:net.minecraft.network.chat.ComponentSerialization#CODEC");
    }

    static String codecs() {
        return "org.stackanvil.packetforge.commons.text.TextCodecs";
    }
}
