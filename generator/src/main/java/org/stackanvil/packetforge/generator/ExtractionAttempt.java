package org.stackanvil.packetforge.generator;

/** A rule can produce a layout, reject a candidate, or leave it to a different rule. */
sealed interface ExtractionAttempt {
    record Matched(ModelLayout layout) implements ExtractionAttempt {}

    record Rejected(String reason) implements ExtractionAttempt {}

    enum NotApplicable implements ExtractionAttempt {
        INSTANCE
    }
}
