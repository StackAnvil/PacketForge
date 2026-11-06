package org.stackanvil.packetforge.transport.login;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;
import org.stackanvil.packetforge.transport.Session;
import org.stackanvil.packetforge.transport.SessionListener;

/** Per-connection login progress. Install a handler in the transport's configure callback. */
public abstract sealed class Login implements SessionListener permits ClientLogin, ServerLogin {
    final Session session;
    final CompletableFuture<GameProfile> authenticated = new CompletableFuture<>();
    final CompletableFuture<Session> ready = new CompletableFuture<>();
    private boolean closed;

    Login(Session session) {
        this.session = session;
    }

    public final Session session() {
        return session;
    }
    /** Completes when login is acknowledged and configuration can begin. */
    public final CompletableFuture<GameProfile> authenticated() {
        return authenticated.copy();
    }
    /** Completes on the first acknowledged transition into PLAY. */
    public final CompletableFuture<Session> ready() {
        return ready.copy();
    }

    final void watch(CompletionStage<?> operation) {
        operation.whenComplete((value, cause) -> {
            if (cause != null) fail(cause);
        });
    }

    final void dispatch(Runnable action) {
        watch(session.execute(() -> {
            if (!closed) action.run();
        }));
    }

    final void fail(Throwable cause) {
        Throwable failure = unwrap(cause);
        authenticated.completeExceptionally(failure);
        ready.completeExceptionally(failure);
        session.disconnect(failure);
    }

    static Throwable unwrap(Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    @Override
    public void disconnected(Session connection, Throwable cause) {
        closed = true;
        Throwable failure = cause == null ? new ClosedChannelException() : cause;
        authenticated.completeExceptionally(failure);
        ready.completeExceptionally(failure);
    }
}
