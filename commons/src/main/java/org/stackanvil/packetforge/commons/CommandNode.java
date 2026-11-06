package org.stackanvil.packetforge.commons;

import net.kyori.adventure.key.Key;

/** Flags, graph references, and the optional payload of a command-tree node. */
public record CommandNode<A extends TypedPayload<Integer>>(int flags, int[] children, int redirect, Stub<A> stub) {
    public sealed interface Stub<A extends TypedPayload<Integer>> permits Literal, Argument {}

    public record Literal<A extends TypedPayload<Integer>>(String name) implements Stub<A> {}

    public record Argument<A extends TypedPayload<Integer>>(String name, A argument, Key suggestion)
            implements Stub<A> {}
}
