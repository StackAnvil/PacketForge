package org.stackanvil.packetforge.generator;

import com.palantir.javaformat.java.Formatter;
import com.palantir.javaformat.java.FormatterException;
import com.palantir.javaformat.java.JavaFormatterOptions;
import java.io.IOException;

/** Applies the project's Java style to emitted source without changing the wire model. */
final class JavaSourceFormatter {
    private static final Formatter FORMATTER = Formatter.createFormatter(JavaFormatterOptions.builder()
            .style(JavaFormatterOptions.Style.PALANTIR)
            .build());

    private JavaSourceFormatter() {}

    static String format(String name, String source) throws IOException {
        try {
            return FORMATTER.formatSource(source);
        } catch (FormatterException failure) {
            throw new IOException("Cannot format generated Java " + name, failure);
        }
    }
}
