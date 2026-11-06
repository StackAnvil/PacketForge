package org.stackanvil.packetforge.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.commons.status.ServerStatus;
import org.stackanvil.packetforge.protocol.GeneratedProtocol;
import org.stackanvil.packetforge.protocol.packet.common.ClientboundKeepAlivePacket;
import org.stackanvil.packetforge.protocol.packet.common.ServerboundKeepAlivePacket;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntent;
import org.stackanvil.packetforge.protocol.packet.handshake.ClientIntentionPacket;
import org.stackanvil.packetforge.protocol.packet.status.ClientboundStatusResponsePacket;
import org.stackanvil.packetforge.protocol.packet.status.ServerboundStatusRequestPacket;

class TcpTransportTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static final class Pair implements AutoCloseable {
        final TcpTransport transport = new TcpTransport();
        final TcpServer listener;
        final Session client;
        final Session server;

        Pair() throws Exception {
            var accepted = new CompletableFuture<Session>();
            listener = await(transport.bind(
                    LOOPBACK,
                    session -> session.addListener(new SessionListener() {
                        @Override
                        public void connected(Session connected) {
                            accepted.complete(connected);
                        }
                    })));
            client = await(transport.connect(listener.localAddress()));
            server = await(accepted);
            await(client.setStates(State.PLAY, State.PLAY));
            await(server.setStates(State.PLAY, State.PLAY));
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    @Test
    void statusHandshakeUsesTheGeneratedProtocolAndAdventureResponse() throws Exception {
        var response = new CompletableFuture<ServerStatus>();
        var status = new ServerStatus(Component.text("PacketForge test server"));
        try (var transport = new TcpTransport();
                var server = await(transport.bind(
                        LOOPBACK,
                        session -> session.onPacket(packet -> {
                            if (packet instanceof ClientIntentionPacket intention) {
                                assertEquals(ClientIntent.STATUS, intention.intention());
                                session.setStates(State.STATUS, State.STATUS);
                            } else if (packet instanceof ServerboundStatusRequestPacket) {
                                session.send(new ClientboundStatusResponsePacket(status));
                            }
                        })))) {
            var address = (InetSocketAddress) server.localAddress();
            Session client = await(transport.connect(
                    address,
                    session -> session.addListener(new SessionListener() {
                        @Override
                        public void connected(Session connection) {
                            connection.setInboundState(State.STATUS);
                            connection.sendAndTransition(
                                    new ClientIntentionPacket(
                                            GeneratedProtocol.PROTOCOL_VERSION,
                                            "localhost",
                                            address.getPort(),
                                            ClientIntent.STATUS),
                                    State.STATUS);
                            connection.send(new ServerboundStatusRequestPacket());
                        }

                        @Override
                        public void packetReceived(Session connection, Packet packet) {
                            response.complete(((ClientboundStatusResponsePacket) packet).status());
                        }
                    })));
            assertEquals(status, await(response));
            assertEquals(State.STATUS, client.inboundState());
            assertEquals(State.STATUS, client.outboundState());
        }
    }

    @Test
    void generatedPacketsTravelInBothDirectionsWithCompressionAndEncryption() throws Exception {
        try (var pair = new Pair()) {
            var received = new CompletableFuture<Long>();
            pair.server.onPacket(packet ->
                    pair.server.send(new ClientboundKeepAlivePacket(((ServerboundKeepAlivePacket) packet).id())));
            pair.client.onPacket(packet -> received.complete(((ClientboundKeepAlivePacket) packet).id()));
            var key = new SecretKeySpec(new byte[16], "AES");
            await(pair.client.setCompressionThreshold(0));
            await(pair.server.setCompressionThreshold(0));
            await(pair.client.enableEncryption(key));
            await(pair.server.enableEncryption(key));
            await(pair.client.send(new ServerboundKeepAlivePacket(Long.MIN_VALUE)));
            assertEquals(Long.MIN_VALUE, await(received));
            assertTrue(pair.client.isEncrypted());
            assertTrue(pair.server.isEncrypted());
            assertEquals(0, pair.client.compressionThreshold());
        }
    }

    @Test
    void concurrentSendersProduceCompleteOrderedPackets() throws Exception {
        try (var pair = new Pair()) {
            var received = new CompletableFuture<List<Long>>();
            var values = new ArrayList<Long>();
            pair.server.onPacket(packet -> {
                values.add(((ServerboundKeepAlivePacket) packet).id());
                if (values.size() == 200) received.complete(List.copyOf(values));
            });
            var sends = new ArrayList<CompletableFuture<Void>>();
            for (long id = 0; id < 200; id++) {
                long value = id;
                sends.add(CompletableFuture.runAsync(() ->
                        pair.client.send(new ServerboundKeepAlivePacket(value)).join()));
            }
            await(CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)));
            List<Long> actual = await(received);
            assertEquals(200, actual.size());
            assertEquals(
                    java.util.stream.LongStream.range(0, 200).boxed().toList(),
                    actual.stream().sorted().toList());
        }
    }

    @Test
    void serverClosureClosesItsChildrenAndLeavesTransportUsable() throws Exception {
        try (var pair = new Pair()) {
            await(pair.listener.closeAsync());
            await(pair.server.closeFuture());
            await(pair.client.closeFuture());
            assertFalse(pair.client.isOpen());
            assertFalse(pair.listener.isOpen());
            try (var other = await(pair.transport.bind(LOOPBACK, ignored -> {}))) {
                assertTrue(other.isOpen());
            }
        }
    }

    @Test
    void refusedConnectionsAndOperationsAfterShutdownFail() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var transport = new TcpTransport();
        try {
            assertThrows(
                    ExecutionException.class, () -> await(transport.connect(new InetSocketAddress("127.0.0.1", port))));
        } finally {
            await(transport.closeAsync());
        }
        assertThrows(ExecutionException.class, () -> await(transport.connect(LOOPBACK)));
        assertThrows(ExecutionException.class, () -> await(transport.bind(LOOPBACK, ignored -> {})));
        await(transport.closeAsync());
    }

    @Test
    void readTimeoutClosesAnIdleConnection() throws Exception {
        try (var transport =
                new TcpTransport(GeneratedProtocol.registry(), 1, Duration.ofSeconds(1), Duration.ofMillis(500))) {
            var disconnected = new CompletableFuture<Throwable>();
            try (var server = await(transport.bind(
                    LOOPBACK,
                    session -> session.addListener(new SessionListener() {
                        @Override
                        public void disconnected(Session session, Throwable cause) {
                            disconnected.complete(cause);
                        }
                    })))) {
                var client = await(transport.connect(server.localAddress()));
                await(client.closeFuture());
                // Either endpoint may time out first; both must finish the disconnect lifecycle.
                await(disconnected);
                assertFalse(client.isOpen());
            }
        }
    }

    @Test
    void shutdownFromAListenerDoesNotBlockTheEventLoop() throws Exception {
        try (var pair = new Pair()) {
            pair.server.onPacket(packet -> pair.transport.close());
            await(pair.client.send(new ServerboundKeepAlivePacket(1)));
            await(pair.transport.closeAsync());
            await(pair.client.closeFuture());
            assertFalse(pair.server.isOpen());
        }
    }

    @Test
    void connectedListenersRunBeforeTheConnectFutureCompletes() throws Exception {
        try (var transport = new TcpTransport();
                var server = await(transport.bind(LOOPBACK, ignored -> {}))) {
            var connected = new CompletableFuture<Void>();
            Session client = await(transport.connect(
                    server.localAddress(),
                    session -> session.addListener(new SessionListener() {
                        @Override
                        public void connected(Session value) {
                            connected.complete(null);
                        }
                    })));
            assertTrue(connected.isDone());
            client.close();
            await(client.closeFuture());
        }
    }

    @Test
    void configurationAndConnectedFailuresReachTheCaller() throws Exception {
        try (var transport = new TcpTransport();
                var server = await(transport.bind(LOOPBACK, ignored -> {}))) {
            var configureFailure = new IllegalStateException("configuration failed");
            var configured = transport.connect(server.localAddress(), session -> {
                throw configureFailure;
            });
            assertSame(
                    configureFailure,
                    assertThrows(ExecutionException.class, () -> await(configured))
                            .getCause());
            var connectedFailure = new IllegalStateException("connected callback failed");
            var connected = transport.connect(
                    server.localAddress(),
                    session -> session.addListener(new SessionListener() {
                        @Override
                        public void connected(Session value) {
                            throw connectedFailure;
                        }
                    }));
            assertSame(
                    connectedFailure,
                    assertThrows(ExecutionException.class, () -> await(connected))
                            .getCause());
        }
    }

    @Test
    void invalidTimeoutsAndThreadCountsFailBeforeAllocatingEventLoops() {
        for (int threads : new int[] {-1, 0})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TcpTransport(
                            GeneratedProtocol.registry(), threads, Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TcpTransport(GeneratedProtocol.registry(), 1, Duration.ZERO, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TcpTransport(GeneratedProtocol.registry(), 1, Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }
}
