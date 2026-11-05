package org.stackanvil.packetforge.generator;

/** Linked schema identities used by validation and emission, independently of diagnostic wire shapes. */
sealed interface SchemaReference {
    record Model(String javaName) implements SchemaReference {}

    record Dispatch(String name) implements SchemaReference {}
}
