package org.stackanvil.packetforge.transport.login;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;

/** Return empty for an unsupported channel, or the answer body without its nullable presence byte. */
@FunctionalInterface
public interface LoginQueryHandler {
    CompletionStage<Optional<byte[]>> answer(Key channel, byte[] data);

    LoginQueryHandler UNSUPPORTED = (channel, data) -> CompletableFuture.completedFuture(Optional.empty());
}
