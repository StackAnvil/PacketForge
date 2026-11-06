package org.stackanvil.packetforge.transport.login;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.protocol.GeneratedProtocol;
import org.stackanvil.packetforge.protocol.packet.common.ClientboundDisconnectPacket;
import org.stackanvil.packetforge.protocol.packet.configuration.ClientboundFinishConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.configuration.ServerboundFinishConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.game.ClientboundStartConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundConfigurationAcknowledgedPacket;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntent;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntentionPacket;
import org.stackanvil.packetforge.protocol.packet.login.*;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;
import org.stackanvil.packetforge.transport.Session;
import org.stackanvil.packetforge.transport.SessionRole;

/** Automates server login. The application supplies configuration packets and decides when to finish. */
public final class ServerLogin extends Login {
    private enum Phase {
        HANDSHAKE,
        HELLO,
        KEY,
        AUTHENTICATING,
        ACKNOWLEDGEMENT,
        CONFIGURATION,
        FINISHING,
        PLAY,
        RECONFIGURING,
        REJECTED
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final KeyPair keys;
    private final ServerAuthenticator authenticator;
    private final int compression;
    private final UUID sessionId;
    private final boolean allowTransfers;
    private final boolean verifyClientAddress;
    private final byte[] challenge = new byte[4];
    private Phase phase = Phase.HANDSHAKE;
    private String username;
    private GameProfile profile;
    private CompletableFuture<Void> pendingTransition;

    private ServerLogin(Session session, Builder options) {
        super(session);
        if (session.role() != SessionRole.SERVER)
            throw new IllegalArgumentException("Server login requires a server session");
        if (session.inboundState() != State.HANDSHAKING || session.outboundState() != State.HANDSHAKING)
            throw new IllegalStateException("Install server login before the handshake");
        keys = options.keys;
        authenticator = options.authenticator;
        compression = options.compression;
        sessionId = options.sessionId;
        allowTransfers = options.allowTransfers;
        verifyClientAddress = options.verifyClientAddress;
        RANDOM.nextBytes(challenge);
        session.addListener(this);
    }

    public static Builder offline() {
        return new Builder(null, null);
    }
    /** Encrypts an offline connection without asking the client to join the session service. */
    public static Builder encrypted(KeyPair keys) {
        return new Builder(Objects.requireNonNull(keys), null);
    }

    public static Builder online(KeyPair keys, ServerAuthenticator authenticator) {
        return new Builder(Objects.requireNonNull(keys), Objects.requireNonNull(authenticator));
    }

    public static final class Builder {
        private final KeyPair keys;
        private final ServerAuthenticator authenticator;
        private int compression = 256;
        private UUID sessionId = UUID.randomUUID();
        private boolean allowTransfers;
        private boolean verifyClientAddress;

        private Builder(KeyPair keys, ServerAuthenticator authenticator) {
            if (keys != null
                    && (!"RSA".equalsIgnoreCase(keys.getPublic().getAlgorithm())
                            || !"RSA".equalsIgnoreCase(keys.getPrivate().getAlgorithm())))
                throw new IllegalArgumentException("Server login requires RSA keys");
            this.keys = keys;
            this.authenticator = authenticator;
        }

        public Builder compressionThreshold(int value) {
            compression = Math.max(-1, value);
            return this;
        }

        public Builder sessionId(UUID value) {
            sessionId = Objects.requireNonNull(value);
            return this;
        }

        public Builder allowTransfers(boolean value) {
            allowTransfers = value;
            return this;
        }
        /** Include the remote address in session-service verification, as vanilla's prevent-proxy-connections setting does. */
        public Builder verifyClientAddress(boolean value) {
            verifyClientAddress = value;
            return this;
        }

        public ServerLogin attach(Session session) {
            return new ServerLogin(Objects.requireNonNull(session), this);
        }
    }

    @Override
    public void packetReceived(Session connection, Packet packet) {
        if (phase == Phase.REJECTED) return;
        if (packet instanceof ClientIntentionPacket intention) {
            require(Phase.HANDSHAKE);
            if (intention.intention() == ClientIntent.STATUS)
                throw new IllegalStateException("A login handler cannot answer status requests");
            phase = Phase.HELLO;
            watch(session.setStates(State.LOGIN, State.LOGIN));
            if (intention.protocolVersion() != GeneratedProtocol.PROTOCOL_VERSION)
                rejectInternal(new LoginException(
                        Component.translatable("multiplayer.disconnect.incompatible", Component.text("26.3"))));
            else if (intention.intention() == ClientIntent.TRANSFER && !allowTransfers)
                rejectInternal(new LoginException(Component.translatable("multiplayer.disconnect.transfers_disabled")));
        } else if (packet instanceof ServerboundHelloPacket hello) {
            require(Phase.HELLO);
            LoginCrypto.validateUsername(hello.name());
            username = hello.name();
            if (keys == null) accept(LoginCrypto.offlineProfile(username));
            else {
                phase = Phase.KEY;
                watch(session.send(new ClientboundHelloPacket(
                        "", keys.getPublic().getEncoded(), challenge.clone(), authenticator != null)));
            }
        } else if (packet instanceof ServerboundKeyPacket response) {
            require(Phase.KEY);
            try {
                var secret = LoginCrypto.sharedSecret(keys.getPrivate(), response, challenge);
                phase = Phase.AUTHENTICATING;
                String hash = LoginCrypto.serverHash("", keys.getPublic(), secret);
                watch(session.enableEncryption(secret).thenRun(() -> {
                    if (authenticator == null) {
                        accept(LoginCrypto.offlineProfile(username));
                        return;
                    }
                    var address = verifyClientAddress && session.remoteAddress() instanceof InetSocketAddress remote
                            ? remote.getAddress()
                            : null;
                    var result = Objects.requireNonNull(authenticator.hasJoinedServer(username, hash, address));
                    result.whenComplete((verified, cause) -> dispatch(() -> {
                        require(Phase.AUTHENTICATING);
                        if (cause != null || verified == null)
                            rejectInternal(new LoginException(
                                    Component.translatable(
                                            cause == null
                                                    ? "multiplayer.disconnect.unverified_username"
                                                    : "multiplayer.disconnect.authservers_down"),
                                    cause == null ? null : unwrap(cause)));
                        else accept(verified);
                    }));
                }));
            } catch (GeneralSecurityException invalid) {
                throw new IllegalArgumentException("Invalid client key response", invalid);
            }
        } else if (packet instanceof ServerboundLoginAcknowledgedPacket) {
            require(Phase.ACKNOWLEDGEMENT);
            phase = Phase.CONFIGURATION;
            watch(session.setStates(State.CONFIGURATION, State.CONFIGURATION)
                    .thenRun(() -> authenticated.complete(profile)));
        } else if (packet instanceof ServerboundFinishConfigurationPacket) {
            require(Phase.FINISHING);
            phase = Phase.PLAY;
            watch(session.setStates(State.PLAY, State.PLAY).thenRun(() -> {
                pendingTransition.complete(null);
                ready.complete(session);
            }));
        } else if (packet instanceof ServerboundConfigurationAcknowledgedPacket) {
            require(Phase.RECONFIGURING);
            phase = Phase.CONFIGURATION;
            watch(session.setInboundState(State.CONFIGURATION).thenRun(() -> pendingTransition.complete(null)));
        }
    }

    private void accept(GameProfile verified) {
        LoginCrypto.validateUsername(verified.name());
        if (!username.equalsIgnoreCase(verified.name()))
            throw new IllegalArgumentException("Authenticated profile does not match the requested username");
        profile = new GameProfile(
                Objects.requireNonNull(verified.id()), verified.name(), List.copyOf(verified.properties()));
        phase = Phase.ACKNOWLEDGEMENT;
        if (compression >= 0)
            watch(session.sendAndSetCompression(new ClientboundLoginCompressionPacket(compression), compression));
        watch(session.send(new ClientboundLoginFinishedPacket(profile, sessionId)));
    }

    /** Sends the terminal configuration packet and completes after the client's acknowledgement. */
    public CompletableFuture<Void> finishConfiguration() {
        return transition(
                Phase.CONFIGURATION, Phase.FINISHING, () -> session.send(new ClientboundFinishConfigurationPacket()));
    }

    /** Begins PLAY to CONFIGURATION and completes when the client acknowledges the inbound switch. */
    public CompletableFuture<Void> beginConfiguration() {
        return transition(
                Phase.PLAY,
                Phase.RECONFIGURING,
                () -> session.sendAndTransition(new ClientboundStartConfigurationPacket(), State.CONFIGURATION));
    }

    private CompletableFuture<Void> transition(Phase expected, Phase next, Supplier<CompletionStage<Void>> write) {
        var result = new CompletableFuture<Void>();
        session.execute(() -> {
                    require(expected);
                    phase = next;
                    pendingTransition = result;
                    watch(write.get());
                })
                .whenComplete((ignored, cause) -> {
                    if (cause != null) result.completeExceptionally(unwrap(cause));
                });
        return result;
    }

    public CompletableFuture<Void> reject(Component reason) {
        Objects.requireNonNull(reason);
        dispatch(() -> rejectInternal(new LoginException(reason)));
        return session.closeFuture();
    }

    private void rejectInternal(LoginException failure) {
        if (phase == Phase.REJECTED) return;
        boolean login = switch (phase) {
            case HANDSHAKE, HELLO, KEY, AUTHENTICATING, ACKNOWLEDGEMENT -> true;
            default -> false;
        };
        phase = Phase.REJECTED;
        authenticated.completeExceptionally(failure);
        ready.completeExceptionally(failure);
        if (pendingTransition != null) pendingTransition.completeExceptionally(failure);
        if (login && session.outboundState() == State.HANDSHAKING) watch(session.setOutboundState(State.LOGIN));
        session.send(
                        login
                                ? new ClientboundLoginDisconnectPacket(failure.reason())
                                : new ClientboundDisconnectPacket(failure.reason()))
                .whenComplete((ignored, cause) -> session.disconnect(failure));
    }

    @Override
    public void disconnected(Session connection, Throwable cause) {
        super.disconnected(connection, cause);
        if (pendingTransition != null)
            pendingTransition.completeExceptionally(cause == null ? new ClosedChannelException() : cause);
    }

    private void require(Phase expected) {
        if (phase != expected)
            throw new IllegalStateException("Unexpected login packet in " + phase + "; expected " + expected);
    }
}
