package org.stackanvil.packetforge.transport.login;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authorizes a selected profile to join the server identified by its signed SHA-1 hash. Return without blocking. */
@FunctionalInterface
public interface ClientAuthenticator {
    CompletionStage<Void> joinServer(UUID profileId, String serverHash);

    /** An offline client must not silently skip authentication requested by an online server. */
    ClientAuthenticator OFFLINE = (profile, hash) -> CompletableFuture.failedFuture(
            new IllegalStateException("This server requires an authenticated Minecraft account"));
}
