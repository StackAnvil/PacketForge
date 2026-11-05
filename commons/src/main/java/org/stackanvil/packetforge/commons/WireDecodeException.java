package org.stackanvil.packetforge.commons;

/** A decoded field violates an explicit wire constraint. */
public final class WireDecodeException extends IllegalArgumentException {
    public WireDecodeException(String message) {
        super(message);
    }
}
