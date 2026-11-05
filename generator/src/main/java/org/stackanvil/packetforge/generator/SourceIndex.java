package org.stackanvil.packetforge.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Resolves explicit imports and nested classes without a Minecraft class loader. */
final class SourceIndex {
    record Source(String name, CompilationUnit unit, TypeDeclaration<?> declaration) {}
    private final Path root;
    private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Map<Path, CompilationUnit> units = new HashMap<>();
    SourceIndex(Path root) { this.root = root; }
    Source source(String name) {
        String top = name;
        Path file = root.resolve(top.replace('.', '/') + ".java");
        while (!Files.exists(file) && top.contains(".")) {
            top = top.substring(0, top.lastIndexOf('.'));
            file = root.resolve(top.replace('.', '/') + ".java");
        }
        if (!Files.exists(file)) throw new Unsupported("Missing source: " + name);
        Path sourceFile = file;
        CompilationUnit unit = units.computeIfAbsent(file, ignored -> {
            try {
                var result = parser.parse(sourceFile);
                if (!result.isSuccessful()) throw new Unsupported("Cannot parse " + sourceFile.getFileName() + ": " + result.getProblems().getFirst().getMessage());
                return result.getResult().orElseThrow();
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        String relative = name.substring(top.lastIndexOf('.') + 1);
        TypeDeclaration<?> declaration = null;
        for (String part : relative.split("\\.")) {
            var members = declaration == null ? unit.getTypes() : declaration.getMembers();
            declaration = members.stream().filter(x -> x instanceof TypeDeclaration<?> t && t.getNameAsString().equals(part))
                .map(x -> (TypeDeclaration<?>) x).findFirst().orElseThrow(() -> new Unsupported("Missing declaration: " + name));
        }
        return new Source(name, unit, declaration);
    }
    String resolve(String name, Source context) {
        if (name.startsWith("net.minecraft.")) return name;
        String first = name.split("\\.")[0];
        for (var imported : context.unit().getImports()) {
            if (!imported.isStatic() && !imported.isAsterisk() && imported.getName().getIdentifier().equals(first))
                return imported.getNameAsString() + name.substring(first.length());
        }
        if (first.equals(context.declaration().getNameAsString())) {
            return context.name().substring(0, context.name().length() - first.length()) + name;
        }
        // An unqualified nested type can refer to a member of the current class.
        if (context.declaration().getMembers().stream().anyMatch(x -> x instanceof TypeDeclaration<?> t && t.getNameAsString().equals(first))) return context.name() + "." + name;
        return context.unit().getPackageDeclaration().orElseThrow().getNameAsString() + "." + name;
    }
    String generated(String name) {
        Source source = source(name);
        String pkg = source.unit().getPackageDeclaration().orElseThrow().getNameAsString();
        String local = name.substring(pkg.length() + 1).replace('.', '_');
        String target = pkg.startsWith("net.minecraft.network.protocol.") ? "packet." + pkg.substring("net.minecraft.network.protocol.".length()) : "type." + pkg.substring("net.minecraft.".length());
        return "org.stackanvil.packetforge.protocol." + target + "." + local;
    }
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) { super(message); }
    }
}
