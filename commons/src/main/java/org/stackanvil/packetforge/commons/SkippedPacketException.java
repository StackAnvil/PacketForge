package org.stackanvil.packetforge.commons;

/** A source codec rejects this packet without requiring the connection to close. */
public final class SkippedPacketException extends IllegalArgumentException {
    public SkippedPacketException(String message) { super(message); }
    public SkippedPacketException(Throwable cause) { super(cause); }
}
