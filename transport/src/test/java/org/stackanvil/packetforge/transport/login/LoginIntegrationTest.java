package org.stackanvil.packetforge.transport.login;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.protocol.packet.common.*;
import org.stackanvil.packetforge.protocol.packet.configuration.*;
import org.stackanvil.packetforge.protocol.packet.cookie.*;
import org.stackanvil.packetforge.protocol.packet.handshake.*;
import org.stackanvil.packetforge.protocol.packet.login.*;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;
import org.stackanvil.packetforge.protocol.type.server.packs.repository.KnownPack;
import org.stackanvil.packetforge.transport.*;

class LoginIntegrationTest {
    static final String USERNAME = "PacketForge";
    static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    static final KeyPair KEYS;

    static {
        try {
            KEYS = LoginCrypto.generateKeyPair();
        } catch (Exception invalid) {
            throw new ExceptionInInitializerError(invalid);
        }
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    static final class Pair implements AutoCloseable {
        final TcpTransport transport = new TcpTransport();
        final TcpServer listener;
        final Session client;
        final ClientLogin clientLogin;
        final ServerLogin serverLogin;

        Pair(ServerLogin.Builder server, Consumer<ClientLogin.Builder> options) throws Exception {
            var accepted = new CompletableFuture<ServerLogin>();
            listener = await(transport.bind(LOOPBACK, session -> accepted.complete(server.attach(session))));
            var attached = new CompletableFuture<ClientLogin>();
            var address = (InetSocketAddress) listener.localAddress();
            var builder = ClientLogin.offline(USERNAME, "localhost", address.getPort());
            options.accept(builder);
            client = await(transport.connect(address, session -> attached.complete(builder.attach(session))));
            clientLogin = await(attached);
            serverLogin = await(accepted);
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    @Test
    void offlineLoginAndBothConfigurationDirectionsWork() throws Exception {
        var called = new AtomicInteger();
        try (var pair = new Pair(
                ServerLogin.offline(),
                builder -> builder.beforePlay(session -> {
                    called.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }))) {
            GameProfile serverProfile = await(pair.serverLogin.authenticated());
            assertEquals(LoginCrypto.offlineProfile(USERNAME), serverProfile);
            assertEquals(serverProfile, await(pair.clientLogin.authenticated()));
            assertEquals(State.CONFIGURATION, pair.client.inboundState());
            assertEquals(State.CONFIGURATION, pair.client.outboundState());
            await(pair.serverLogin.finishConfiguration());
            assertSame(pair.client, await(pair.clientLogin.ready()));
            await(pair.serverLogin.ready());
            assertEquals(State.PLAY, pair.client.outboundState());
            assertEquals(State.PLAY, pair.serverLogin.session().inboundState());
            await(pair.serverLogin.beginConfiguration());
            assertEquals(State.CONFIGURATION, pair.client.inboundState());
            assertEquals(State.CONFIGURATION, pair.serverLogin.session().inboundState());
            await(pair.serverLogin.finishConfiguration());
            assertEquals(2, called.get());
            assertEquals(State.PLAY, pair.client.inboundState());
            assertFalse(pair.client.isEncrypted());
            assertEquals(256, pair.client.compressionThreshold());
        }
    }

    @Test
    void onlineLoginWaitsForBothAuthenticationStagesAndUsesTheSameHash() throws Exception {
        var clientJoined = new CompletableFuture<Void>();
        var serverVerified = new CompletableFuture<GameProfile>();
        var clientHash = new CompletableFuture<String>();
        var serverHash = new CompletableFuture<String>();
        var verifiedProfile = new GameProfile(UUID.randomUUID(), USERNAME, List.of());
        var server = ServerLogin.online(KEYS, (username, hash, address) -> {
                    assertEquals(USERNAME, username);
                    assertNotNull(address);
                    serverHash.complete(hash);
                    return serverVerified;
                })
                .verifyClientAddress(true)
                .compressionThreshold(0);
        try (var pair = new Pair(
                server,
                builder -> builder.authenticator((id, hash) -> {
                    assertEquals(LoginCrypto.offlineProfile(USERNAME).id(), id);
                    clientHash.complete(hash);
                    return clientJoined;
                }))) {
            String sentHash = await(clientHash);
            assertFalse(pair.client.isEncrypted());
            assertFalse(pair.serverLogin.authenticated().isDone());
            clientJoined.complete(null);
            assertEquals(sentHash, await(serverHash));
            assertTrue(pair.client.isEncrypted());
            assertTrue(pair.serverLogin.session().isEncrypted());
            assertFalse(pair.clientLogin.authenticated().isDone());
            serverVerified.complete(verifiedProfile);
            assertEquals(verifiedProfile, await(pair.clientLogin.authenticated()));
            assertEquals(verifiedProfile, await(pair.serverLogin.authenticated()));
            await(pair.serverLogin.finishConfiguration());
            await(pair.clientLogin.ready());
            assertEquals(0, pair.client.compressionThreshold());
        }
    }

    @Test
    void encryptedOfflineLoginDoesNotCallTheSessionService() throws Exception {
        try (var pair = new Pair(
                ServerLogin.encrypted(KEYS).compressionThreshold(-1),
                builder -> builder.authenticator((id, hash) -> {
                    throw new AssertionError("Offline encryption must not request authentication");
                }))) {
            await(pair.serverLogin.authenticated());
            await(pair.clientLogin.authenticated());
            assertTrue(pair.client.isEncrypted());
            assertEquals(-1, pair.client.compressionThreshold());
            await(pair.serverLogin.finishConfiguration());
            await(pair.clientLogin.ready());
        }
    }

    @Test
    void asynchronousConfigurationWorkFinishesBeforeAcknowledgement() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        try (var pair = new Pair(
                ServerLogin.offline(),
                builder -> builder.beforePlay(session -> {
                    entered.complete(null);
                    return release;
                }))) {
            await(pair.serverLogin.authenticated());
            var finishing = pair.serverLogin.finishConfiguration();
            await(entered);
            assertFalse(finishing.isDone());
            assertFalse(pair.clientLogin.ready().isDone());
            assertEquals(State.CONFIGURATION, pair.serverLogin.session().outboundState());
            assertEquals(State.CONFIGURATION, pair.client.outboundState());
            release.complete(null);
            await(finishing);
            await(pair.clientLogin.ready());
            assertEquals(State.PLAY, pair.serverLogin.session().outboundState());
        }
    }

    @Test
    void authenticationFailureClosesBothEndpointsWithoutFinishingLogin() throws Exception {
        var requested = new CompletableFuture<Void>();
        var joined = new CompletableFuture<Void>();
        try (var pair = new Pair(
                ServerLogin.online(KEYS, (username, hash, address) -> {
                    throw new AssertionError("The key response must not be sent after client authentication failure");
                }),
                builder -> builder.authenticator((id, hash) -> {
                    requested.complete(null);
                    return joined;
                }))) {
            await(requested);
            var failure = new IllegalStateException("invalid test session");
            joined.completeExceptionally(failure);
            assertSame(
                    failure,
                    assertThrows(ExecutionException.class, () -> await(pair.clientLogin.ready()))
                            .getCause());
            await(pair.client.closeFuture());
            await(pair.serverLogin.session().closeFuture());
            assertFalse(pair.client.isEncrypted());
            assertFalse(pair.serverLogin.session().isOpen());
        }
    }

    @Test
    void unverifiedAccountReceivesAnAdventureRejection() throws Exception {
        try (var pair = new Pair(
                ServerLogin.online(KEYS, (username, hash, address) -> CompletableFuture.completedFuture(null)),
                builder -> builder.authenticator((id, hash) -> CompletableFuture.completedFuture(null)))) {
            var failure = assertThrows(ExecutionException.class, () -> await(pair.clientLogin.authenticated()));
            assertInstanceOf(LoginException.class, failure.getCause());
            assertEquals(
                    Component.translatable("multiplayer.disconnect.unverified_username"),
                    ((LoginException) failure.getCause()).reason());
            await(pair.client.closeFuture());
        }
    }

    @Test
    void offlineClientCannotBypassAnOnlineServer() throws Exception {
        try (var pair = new Pair(
                ServerLogin.online(KEYS, (username, hash, address) -> {
                    throw new AssertionError("No key response expected");
                }),
                ignored -> {})) {
            assertThrows(ExecutionException.class, () -> await(pair.clientLogin.authenticated()));
            await(pair.client.closeFuture());
            assertFalse(pair.client.isEncrypted());
        }
    }

    @Test
    void transferPolicyIsExplicit() throws Exception {
        try (var denied = new Pair(ServerLogin.offline(), builder -> builder.transfer(true))) {
            var cause = assertThrows(ExecutionException.class, () -> await(denied.clientLogin.authenticated()))
                    .getCause();
            assertEquals(
                    Component.translatable("multiplayer.disconnect.transfers_disabled"),
                    ((LoginException) cause).reason());
        }
        try (var allowed = new Pair(ServerLogin.offline().allowTransfers(true), builder -> builder.transfer(true))) {
            await(allowed.clientLogin.authenticated());
            await(allowed.serverLogin.authenticated());
        }
    }

    @Test
    void configurationPacketsRemainAvailableAndCommonRepliesAreAutomatic() throws Exception {
        var cookie = Key.key("packetforge:test");
        var store = CookieStore.inMemory();
        var known = new KnownPack("minecraft", "core", "26.3");
        try (var pair = new Pair(
                ServerLogin.offline(), builder -> builder.cookies(store).knownPacks(packs -> packs))) {
            await(pair.serverLogin.authenticated());
            var replies = new CompletableFuture<List<Packet>>();
            var values = new java.util.ArrayList<Packet>();
            pair.serverLogin.session().onPacket(packet -> {
                values.add(packet);
                if (values.size() == 4) replies.complete(List.copyOf(values));
            });
            await(pair.serverLogin.session().send(new ClientboundStoreCookiePacket(cookie, new byte[] {1, 2, 3})));
            await(pair.serverLogin.session().send(new ClientboundCookieRequestPacket(cookie)));
            await(pair.serverLogin.session().send(new ClientboundSelectKnownPacks(List.of(known))));
            await(pair.serverLogin.session().send(new ClientboundKeepAlivePacket(123)));
            await(pair.serverLogin.session().send(new ClientboundPingPacket(7)));
            List<Packet> actual = await(replies);
            assertArrayEquals(new byte[] {1, 2, 3}, ((ServerboundCookieResponsePacket) actual.get(0)).payload());
            assertEquals(new ServerboundSelectKnownPacks(List.of(known)), actual.get(1));
            assertEquals(new ServerboundKeepAlivePacket(123), actual.get(2));
            assertEquals(new ServerboundPongPacket(7), actual.get(3));
            byte[] saved = store.get(cookie).orElseThrow();
            saved[0] = 9;
            assertEquals(1, store.get(cookie).orElseThrow()[0]);
        }
    }

    @Test
    void loginQueriesEncodeTheirNullablePresenceByte() throws Exception {
        for (boolean supported : new boolean[] {false, true}) {
            var authenticated = new CompletableFuture<GameProfile>();
            var verifying = new CompletableFuture<Void>();
            try (var pair = new Pair(
                    ServerLogin.online(KEYS, (username, hash, address) -> {
                        verifying.complete(null);
                        return authenticated;
                    }),
                    builder -> builder.authenticator((id, hash) -> CompletableFuture.completedFuture(null))
                            .queries((channel, data) -> CompletableFuture.completedFuture(
                                    supported ? Optional.of(new byte[] {3, 4}) : Optional.empty())))) {
                var answer = new CompletableFuture<ServerboundCustomQueryAnswerPacket>();
                pair.serverLogin.session().onPacket(packet -> {
                    if (packet instanceof ServerboundCustomQueryAnswerPacket query) answer.complete(query);
                });
                await(verifying);
                await(pair.serverLogin
                        .session()
                        .send(new ClientboundCustomQueryPacket(31, Key.key("packetforge:test"), new byte[] {1, 2})));
                ServerboundCustomQueryAnswerPacket received = await(answer);
                assertEquals(31, received.transactionId());
                assertArrayEquals(supported ? new byte[] {1, 3, 4} : new byte[] {0}, received.data());
                authenticated.complete(LoginCrypto.offlineProfile(USERNAME));
                await(pair.clientLogin.authenticated());
            }
        }
    }

    @Test
    void invalidClientChallengeNeverEnablesEncryptionOrAuthenticates() throws Exception {
        try (var transport = new TcpTransport()) {
            var accepted = new CompletableFuture<ServerLogin>();
            try (var listener = await(transport.bind(
                    LOOPBACK,
                    session -> accepted.complete(ServerLogin.online(KEYS, (username, hash, address) -> {
                                throw new AssertionError("Invalid challenge must not reach the verifier");
                            })
                            .attach(session))))) {
                var hello = new CompletableFuture<ClientboundHelloPacket>();
                var client = await(transport.connect(
                        listener.localAddress(),
                        session -> session.addListener(new SessionListener() {
                            @Override
                            public void connected(Session connection) {
                                connection.setInboundState(State.LOGIN);
                                connection.sendAndTransition(
                                        new ClientIntentionPacket(
                                                org.stackanvil.packetforge.protocol.GeneratedProtocol.PROTOCOL_VERSION,
                                                "localhost",
                                                25565,
                                                ClientIntent.LOGIN),
                                        State.LOGIN);
                                connection.send(new ServerboundHelloPacket(
                                        USERNAME,
                                        LoginCrypto.offlineProfile(USERNAME).id()));
                            }

                            @Override
                            public void packetReceived(Session connection, Packet packet) {
                                if (packet instanceof ClientboundHelloPacket request) hello.complete(request);
                            }
                        })));
                var request = await(hello);
                byte[] wrong = request.challenge().clone();
                wrong[0] ^= 1;
                await(client.send(LoginCrypto.keyResponse(
                        LoginCrypto.publicKey(request.publicKey()), LoginCrypto.generateSecretKey(), wrong)));
                var server = await(accepted);
                assertThrows(ExecutionException.class, () -> await(server.authenticated()));
                await(server.session().closeFuture());
                assertFalse(server.session().isEncrypted());
                await(client.closeFuture());
            }
        }
    }

    @Test
    void failedConfigurationWorkClosesPendingTransitionFutures() throws Exception {
        var gate = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        try (var pair = new Pair(
                ServerLogin.offline(),
                builder -> builder.beforePlay(session -> {
                    entered.complete(null);
                    return gate;
                }))) {
            await(pair.serverLogin.authenticated());
            var finish = pair.serverLogin.finishConfiguration();
            await(entered);
            var failure = new IllegalStateException("registry setup failed");
            gate.completeExceptionally(failure);
            assertSame(
                    failure,
                    assertThrows(ExecutionException.class, () -> await(pair.clientLogin.ready()))
                            .getCause());
            assertThrows(ExecutionException.class, () -> await(finish));
            await(pair.serverLogin.session().closeFuture());
        }
    }

    @Test
    void configurationRejectionsUseTheCommonAdventureDisconnectPacket() throws Exception {
        var received = new CompletableFuture<Component>();
        try (var pair = new Pair(ServerLogin.offline(), ignored -> {})) {
            await(pair.serverLogin.authenticated());
            pair.client.onPacket(packet -> {
                if (packet instanceof ClientboundDisconnectPacket rejected) received.complete(rejected.reason());
            });
            Component reason = Component.text("Configuration rejected");
            await(pair.serverLogin.reject(reason));
            assertEquals(reason, await(received));
            var failure = assertThrows(ExecutionException.class, () -> await(pair.clientLogin.ready()))
                    .getCause();
            assertEquals(reason, ((LoginException) failure).reason());
        }
    }

    @Test
    void repeatedLocalTransitionsFailWithoutClosingAHealthySession() throws Exception {
        try (var pair = new Pair(ServerLogin.offline(), ignored -> {})) {
            await(pair.serverLogin.authenticated());
            assertThrows(ExecutionException.class, () -> await(pair.serverLogin.beginConfiguration()));
            await(pair.serverLogin.finishConfiguration());
            assertThrows(ExecutionException.class, () -> await(pair.serverLogin.finishConfiguration()));
            assertTrue(pair.client.isOpen());
        }
    }
}
