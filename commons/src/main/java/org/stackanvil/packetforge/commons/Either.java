package org.stackanvil.packetforge.commons;

import java.util.Objects;

/** Exactly one branch of a wire union. */
public sealed interface Either<L, R> {
    record Left<L, R>(L value) implements Either<L, R> {
        public Left {
            Objects.requireNonNull(value);
        }
    }

    record Right<L, R>(R value) implements Either<L, R> {
        public Right {
            Objects.requireNonNull(value);
        }
    }

    static <L, R> Either<L, R> left(L value) {
        return new Left<>(value);
    }

    static <L, R> Either<L, R> right(R value) {
        return new Right<>(value);
    }
}
