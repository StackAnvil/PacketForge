package org.stackanvil.packetforge.transport.login;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/** A login rejection retains the peer's Adventure reason without converting it to raw NBT. */
public final class LoginException extends RuntimeException {
    private final Component reason;

    public LoginException(Component reason) {
        this(reason, null);
    }

    public LoginException(Component reason, Throwable cause) {
        super("Minecraft login rejected", cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Component reason() {
        return reason;
    }
}
