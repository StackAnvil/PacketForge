package org.stackanvil.packetforge.commons;

/** Two wire values carried together by an imperative layout. */
public record Pair<A, B>(A first, B second) {}
