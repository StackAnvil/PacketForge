package org.stackanvil.packetforge.commons;

/** Flags, graph references, and the optional payload of a command-tree node. */
public record CommandNode(int flags, int[] children, int redirect, Stub stub) {
    public sealed interface Stub permits Literal, Argument {}
    public record Literal(String name) implements Stub {}
    public record Argument(String name, Variant<Integer> argument, String suggestion) implements Stub {}
}
