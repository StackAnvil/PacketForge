package org.stackanvil.packetforge.transport.login;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.protocol.GeneratedProtocol;
import org.stackanvil.packetforge.protocol.packet.common.*;
import org.stackanvil.packetforge.protocol.packet.configuration.*;
import org.stackanvil.packetforge.protocol.packet.cookie.*;
import org.stackanvil.packetforge.protocol.packet.game.ClientboundStartConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.game.ServerboundConfigurationAcknowledgedPacket;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntent;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntentionPacket;
import org.stackanvil.packetforge.protocol.packet.login.*;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;
import org.stackanvil.packetforge.protocol.type.server.packs.repository.KnownPack;
import org.stackanvil.packetforge.transport.Session;
import org.stackanvil.packetforge.transport.SessionRole;

/** Automates client login, key exchange, and acknowledged configuration transitions. */
public final class ClientLogin extends Login {
    private enum Phase {
        NEW,
        LOGIN,
        AUTHENTICATING,
        CONFIGURATION,
        SWITCHING,
        PLAY
    }

    private final GameProfile profile;
    private final String host;
    private final int port;
    private final ClientIntent intention;
    private final ClientAuthenticator authenticator;
    private final LoginQueryHandler queries;
    private final CookieStore cookies;
    private final Function<List<KnownPack>, List<KnownPack>> knownPacks;
    private final Function<Session, CompletionStage<Void>> beforePlay;
    private final HashSet<Integer> pendingQueries = new HashSet<>();
    private Phase phase = Phase.NEW;
    private boolean receivedHello;

    private ClientLogin(Session session, Builder options) {
        super(session);
        if (session.role() != SessionRole.CLIENT)
            throw new IllegalArgumentException("Client login requires a client session");
        if (session.inboundState() != State.HANDSHAKING || session.outboundState() != State.HANDSHAKING)
            throw new IllegalStateException("Install client login before the handshake");
        profile = options.profile;
        host = options.host;
        port = options.port;
        intention = options.intention;
        authenticator = options.authenticator;
        queries = options.queries;
        cookies = options.cookies;
        knownPacks = options.knownPacks;
        beforePlay = options.beforePlay;
        session.addListener(this);
        dispatch(() -> {
            if (session.isOpen()) start();
        });
    }

    public static Builder builder(GameProfile profile, String host, int port) {
        return new Builder(profile, host, port);
    }

    public static Builder offline(String username, String host, int port) {
        return builder(LoginCrypto.offlineProfile(username), host, port);
    }

    public static final class Builder {
        private final GameProfile profile;
        private final String host;
        private final int port;
        private ClientIntent intention = ClientIntent.LOGIN;
        private ClientAuthenticator authenticator = ClientAuthenticator.OFFLINE;
        private LoginQueryHandler queries = LoginQueryHandler.UNSUPPORTED;
        private CookieStore cookies = CookieStore.inMemory();
        private Function<List<KnownPack>, List<KnownPack>> knownPacks = ignored -> List.of();
        private Function<Session, CompletionStage<Void>> beforePlay =
                ignored -> CompletableFuture.completedFuture(null);

        private Builder(GameProfile profile, String host, int port) {
            Objects.requireNonNull(profile);
            LoginCrypto.validateUsername(profile.name());
            this.profile = new GameProfile(
                    Objects.requireNonNull(profile.id()), profile.name(), List.copyOf(profile.properties()));
            this.host = Objects.requireNonNull(host);
            if (host.length() > 255 || port < 0 || port > 65535)
                throw new IllegalArgumentException("Invalid handshake address");
            this.port = port;
        }

        public Builder authenticator(ClientAuthenticator value) {
            authenticator = Objects.requireNonNull(value);
            return this;
        }

        public Builder queries(LoginQueryHandler value) {
            queries = Objects.requireNonNull(value);
            return this;
        }

        public Builder cookies(CookieStore value) {
            cookies = Objects.requireNonNull(value);
            return this;
        }
        /** Select only packs already available to the application. Empty requests full registry data. */
        public Builder knownPacks(Function<List<KnownPack>, List<KnownPack>> value) {
            knownPacks = Objects.requireNonNull(value);
            return this;
        }
        /** Called for each configuration finish; its asynchronous work must finish before acknowledging PLAY. */
        public Builder beforePlay(Function<Session, CompletionStage<Void>> value) {
            beforePlay = Objects.requireNonNull(value);
            return this;
        }

        public Builder transfer(boolean value) {
            intention = value ? ClientIntent.TRANSFER : ClientIntent.LOGIN;
            return this;
        }

        public ClientLogin attach(Session session) {
            return new ClientLogin(Objects.requireNonNull(session), this);
        }
    }

    @Override
    public void connected(Session connection) {
        start();
    }

    private void start() {
        if (phase != Phase.NEW) return;
        phase = Phase.LOGIN;
        watch(session.setInboundState(State.LOGIN));
        watch(session.sendAndTransition(
                new ClientIntentionPacket(GeneratedProtocol.PROTOCOL_VERSION, host, port, intention), State.LOGIN));
        watch(session.send(new ServerboundHelloPacket(profile.name(), profile.id())));
    }

    @Override
    public void packetReceived(Session connection, Packet packet) {
        if (packet instanceof ClientboundLoginDisconnectPacket rejected) {
            fail(new LoginException(rejected.reason()));
            return;
        }
        if (packet instanceof ClientboundDisconnectPacket rejected) {
            fail(new LoginException(rejected.reason()));
            return;
        }
        if (packet instanceof ClientboundCookieRequestPacket request) {
            watch(session.send(new ServerboundCookieResponsePacket(
                    request.key(), cookies.get(request.key()).orElse(null))));
        } else if (packet instanceof ClientboundStoreCookiePacket cookie) {
            cookies.put(cookie.key(), cookie.payload());
        } else if (packet instanceof ClientboundHelloPacket hello) {
            encrypt(hello);
        } else if (packet instanceof ClientboundLoginCompressionPacket compression) {
            require(Phase.LOGIN);
            watch(session.setCompressionThreshold(compression.compressionThreshold()));
        } else if (packet instanceof ClientboundCustomQueryPacket query) {
            answer(query);
        } else if (packet instanceof ClientboundLoginFinishedPacket finished) {
            require(Phase.LOGIN);
            if (!pendingQueries.isEmpty()) throw new IllegalStateException("Login finished with unanswered queries");
            phase = Phase.CONFIGURATION;
            watch(session.setInboundState(State.CONFIGURATION));
            watch(session.sendAndTransition(new ServerboundLoginAcknowledgedPacket(), State.CONFIGURATION)
                    .thenRun(() -> authenticated.complete(finished.gameProfile())));
        } else if (packet instanceof ClientboundSelectKnownPacks packs) {
            require(Phase.CONFIGURATION);
            List<KnownPack> selected = List.copyOf(knownPacks.apply(List.copyOf(packs.knownPacks())));
            if (!packs.knownPacks().containsAll(selected) || new HashSet<>(selected).size() != selected.size())
                throw new IllegalArgumentException("Selected packs must be a unique subset of the server's packs");
            watch(session.send(new ServerboundSelectKnownPacks(selected)));
        } else if (packet instanceof ClientboundFinishConfigurationPacket) {
            require(Phase.CONFIGURATION);
            phase = Phase.SWITCHING;
            Objects.requireNonNull(beforePlay.apply(session)).whenComplete((ignored, cause) -> {
                if (cause != null) fail(cause);
                else
                    dispatch(() -> {
                        require(Phase.SWITCHING);
                        phase = Phase.PLAY;
                        watch(session.setInboundState(State.PLAY));
                        watch(session.sendAndTransition(new ServerboundFinishConfigurationPacket(), State.PLAY)
                                .thenRun(() -> ready.complete(session)));
                    });
            });
        } else if (packet instanceof ClientboundStartConfigurationPacket) {
            require(Phase.PLAY);
            phase = Phase.CONFIGURATION;
            watch(session.setInboundState(State.CONFIGURATION));
            watch(session.sendAndTransition(new ServerboundConfigurationAcknowledgedPacket(), State.CONFIGURATION));
        } else if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
            watch(session.send(new ServerboundKeepAlivePacket(keepAlive.id())));
        } else if (packet instanceof ClientboundPingPacket ping) {
            watch(session.send(new ServerboundPongPacket(ping.id())));
        }
    }

    private void encrypt(ClientboundHelloPacket hello) {
        require(Phase.LOGIN);
        if (receivedHello) throw new IllegalStateException("Duplicate encryption request");
        receivedHello = true;
        phase = Phase.AUTHENTICATING;
        try {
            var key = LoginCrypto.publicKey(hello.publicKey());
            var secret = LoginCrypto.generateSecretKey();
            var answer = LoginCrypto.keyResponse(key, secret, hello.challenge());
            CompletionStage<Void> joined = hello.shouldAuthenticate()
                    ? Objects.requireNonNull(authenticator.joinServer(
                            profile.id(), LoginCrypto.serverHash(hello.serverId(), key, secret)))
                    : CompletableFuture.completedFuture(null);
            joined.whenComplete((ignored, cause) -> {
                if (cause != null) fail(cause);
                else
                    dispatch(() -> {
                        require(Phase.AUTHENTICATING);
                        phase = Phase.LOGIN;
                        watch(session.sendAndEnableEncryption(answer, secret));
                    });
            });
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("Invalid server encryption request", invalid);
        }
    }

    private void answer(ClientboundCustomQueryPacket query) {
        if (phase != Phase.LOGIN && phase != Phase.AUTHENTICATING)
            throw new IllegalStateException("Query outside login");
        if (!pendingQueries.add(query.transactionId())) throw new IllegalStateException("Duplicate login query ID");
        Objects.requireNonNull(queries.answer(query.channel(), query.data().clone()))
                .whenComplete((answer, cause) -> {
                    if (cause != null) fail(cause);
                    else
                        dispatch(() -> {
                            if (!pendingQueries.remove(query.transactionId())) return;
                            byte[] body;
                            if (answer.isEmpty()) body = new byte[] {0};
                            else {
                                byte[] value = answer.orElseThrow();
                                if (value.length > 1048575)
                                    throw new IllegalArgumentException("Login query answer exceeds 1 MiB");
                                body = new byte[value.length + 1];
                                body[0] = 1;
                                System.arraycopy(value, 0, body, 1, value.length);
                            }
                            watch(session.send(new ServerboundCustomQueryAnswerPacket(query.transactionId(), body)));
                        });
                });
    }

    private void require(Phase expected) {
        if (phase != expected)
            throw new IllegalStateException("Unexpected login packet in " + phase + "; expected " + expected);
    }
}
