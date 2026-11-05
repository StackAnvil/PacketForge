package org.stackanvil.packetforge.generator;

import java.util.List;
import org.stackanvil.packetforge.generator.Analyzer.Field;
import org.stackanvil.packetforge.generator.Analyzer.Program;

/** The extracted serialization layout before source identity and model caching are attached. */
record ModelLayout(List<Field> fields, Program program, List<String> validations, String decodeFailure) {
    ModelLayout {
        fields = List.copyOf(fields);
        validations = List.copyOf(validations);
    }

    static ModelLayout fields(List<Field> fields) {
        return new ModelLayout(fields, null, List.of(), "");
    }

    static ModelLayout program(ProgramLowerer.Lowered lowered) {
        return new ModelLayout(lowered.fields(), lowered.program(), List.of(), "");
    }
}
