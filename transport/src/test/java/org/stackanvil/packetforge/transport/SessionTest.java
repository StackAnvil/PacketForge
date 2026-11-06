package org.stackanvil.packetforge.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketCodec;
import org.stackanvil.packetforge.commons.PacketRegistry;
import org.stackanvil.packetforge.commons.PacketRegistry.Flow;
import org.stackanvil.packetforge.commons.PacketRegistry.Key;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.commons.SkippedPacketException;
import org.stackanvil.packetforge.commons.WireCodecs;

class SessionTest {
    record NumberPacket(int number) implements Packet {
        @Override
        public void serialize(ByteBuf output) {
            WireCodecs.VAR_INT.encode(output, number);
        }
    }

    static final PacketCodec<NumberPacket> CODEC = new PacketCodec<>() {
        public NumberPacket decode(ByteBuf input) {
            return new NumberPacket(WireCodecs.VAR_INT.decode(input));
        }

        public void encode(ByteBuf output, NumberPacket value) {
            value.serialize(output);
        }
    };

    static PacketRegistry registry() {
        var entries = new ArrayList<PacketRegistry.Entry<?>>();
        for (State state : State.values())
            for (Flow flow : Flow.values())
                entries.add(new PacketRegistry.Entry<>(
                        new Key(state, flow, state.ordinal()), "number", NumberPacket.class, CODEC));
        return new PacketRegistry(entries);
    }

    static final class Connection implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel(false, false);
        final Session session;

        Connection(PacketRegistry registry, SessionRole role, SessionListener... listeners) throws Exception {
            session = new Session(channel, registry, role, Duration.ZERO);
            for (var listener : listeners) session.addListener(listener);
            channel.register();
        }

        Connection(SessionRole role, SessionListener... listeners) throws Exception {
            this(registry(), role, listeners);
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void changesApplyBetweenConcatenatedFrames() throws Exception {
        var received = new ArrayList<Integer>();
        try (var receiver = new Connection(SessionRole.SERVER, new SessionListener() {
                    @Override
                    public void packetReceived(Session session, Packet packet) {
                        int number = ((NumberPacket) packet).number();
                        received.add(number);
                        if (number == 1) {
                            session.setInboundState(State.PLAY);
                            session.setCompressionThreshold(0);
                        }
                    }
                });
                var sender = new Connection(SessionRole.CLIENT)) {
            sender.session.sendAndSetCompression(new NumberPacket(1), 0).join();
            sender.session.setOutboundState(State.PLAY).join();
            sender.session.send(new NumberPacket(2)).join();
            ByteBuf combined = Unpooled.buffer();
            ByteBuf next;
            while ((next = sender.channel.readOutbound()) != null) {
                combined.writeBytes(next);
                next.release();
            }
            receiver.channel.writeInbound(combined);
            assertEquals(List.of(1, 2), received);
            assertTrue(receiver.session.isOpen());
            assertEquals(State.PLAY, receiver.session.inboundState());
            assertEquals(State.HANDSHAKING, receiver.session.outboundState());
        }
    }

    @Test
    void transitionCallbacksCannotOvertakeTheAcknowledgement() throws Exception {
        var sent = new ArrayList<Integer>();
        try (var connection = new Connection(SessionRole.CLIENT, new SessionListener() {
            @Override
            public void stateChanged(Session session, State inbound, State outbound) {
                session.send(new NumberPacket(2));
            }

            @Override
            public void packetSent(Session session, Packet packet) {
                sent.add(((NumberPacket) packet).number());
            }
        })) {
            connection
                    .session
                    .sendAndTransition(new NumberPacket(1), State.PLAY)
                    .join();
            assertEquals(List.of(1, 2), sent);
            var frameDecoder = new EmbeddedChannel(new FrameDecoder());
            try {
                frameDecoder.writeInbound(
                        (ByteBuf) connection.channel.readOutbound(), (ByteBuf) connection.channel.readOutbound());
                ByteBuf first = frameDecoder.readInbound();
                ByteBuf second = frameDecoder.readInbound();
                try {
                    assertEquals(new NumberPacket(1), registry().decode(State.HANDSHAKING, Flow.SERVERBOUND, first));
                    assertEquals(new NumberPacket(2), registry().decode(State.PLAY, Flow.SERVERBOUND, second));
                } finally {
                    first.release();
                    second.release();
                }
            } finally {
                frameDecoder.finishAndReleaseAll();
            }
        }
    }

    @Test
    void encryptionStartsAfterThePlaintextKeyResponse() throws Exception {
        var key = new SecretKeySpec(new byte[16], "AES");
        var received = new ArrayList<Integer>();
        try (var receiver = new Connection(SessionRole.SERVER, new SessionListener() {
                    @Override
                    public void packetReceived(Session session, Packet packet) {
                        received.add(((NumberPacket) packet).number());
                    }
                });
                var sender = new Connection(SessionRole.CLIENT)) {
            sender.session.sendAndEnableEncryption(new NumberPacket(1), key).join();
            receiver.channel.writeInbound((ByteBuf) sender.channel.readOutbound());
            receiver.session.enableEncryption(key).join();
            sender.session.send(new NumberPacket(2)).join();
            receiver.channel.writeInbound((ByteBuf) sender.channel.readOutbound());
            assertEquals(List.of(1, 2), received);
            assertTrue(sender.session.isEncrypted());
            assertThrows(
                    CompletionException.class,
                    () -> sender.session
                            .sendAndEnableEncryption(new NumberPacket(3), key)
                            .join());
            assertNull(sender.channel.readOutbound());
            assertTrue(sender.session.isOpen());
        }
    }

    @Test
    void skippedPacketsDoNotDisconnectOrDiscardTheNextFrame() throws Exception {
        var entries = new ArrayList<>(registry().entries().values());
        entries.add(new PacketRegistry.Entry<>(
                new Key(State.HANDSHAKING, Flow.SERVERBOUND, 42), "skipped", NumberPacket.class, new PacketCodec<>() {
                    public NumberPacket decode(ByteBuf input) {
                        throw new SkippedPacketException("ignored");
                    }

                    public void encode(ByteBuf output, NumberPacket value) {
                        throw new SkippedPacketException("ignored");
                    }
                }));
        var events = new ArrayList<String>();
        try (var connection = new Connection(new PacketRegistry(entries), SessionRole.SERVER, new SessionListener() {
            @Override
            public void packetSkipped(Session session, SkippedPacketException cause) {
                events.add(cause.getMessage());
            }

            @Override
            public void packetReceived(Session session, Packet packet) {
                events.add("received");
            }
        })) {
            connection.channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 42, 2, 0, 7}));
            assertEquals(List.of("ignored", "received"), events);
            assertTrue(connection.session.isOpen());
        }
    }

    @Test
    void malformedPacketsDisconnectExactlyOnceAndNotifyRemainingListeners() throws Exception {
        for (byte[] malformed : new byte[][] {{1, 100}, {3, 0, 1, 2}, {0}}) {
            var causes = new ArrayList<Throwable>();
            try (var connection = new Connection(
                    SessionRole.SERVER,
                    new SessionListener() {
                        @Override
                        public void disconnected(Session session, Throwable cause) {
                            throw new IllegalStateException("listener failure");
                        }
                    },
                    new SessionListener() {
                        @Override
                        public void disconnected(Session session, Throwable cause) {
                            causes.add(cause);
                        }
                    })) {
                connection.channel.writeInbound(Unpooled.wrappedBuffer(malformed));
                connection.session.closeAsync().join();
                assertFalse(connection.session.isOpen());
                assertEquals(1, causes.size());
                assertNotNull(causes.getFirst());
            }
        }
    }

    @Test
    void wrongLocalPacketsFailWithoutChangingTheStream() throws Exception {
        try (var connection = new Connection(SessionRole.CLIENT)) {
            assertThrows(
                    CompletionException.class,
                    () -> connection
                            .session
                            .send(new Key(State.PLAY, Flow.SERVERBOUND, 4), new NumberPacket(1))
                            .join());
            assertThrows(
                    CompletionException.class,
                    () -> connection
                            .session
                            .send(new Key(State.HANDSHAKING, Flow.CLIENTBOUND, 0), new NumberPacket(1))
                            .join());
            assertTrue(connection.session.isOpen());
            assertNull(connection.channel.readOutbound());
            connection.session.send(new NumberPacket(2)).join();
            ByteBuf encoded = connection.channel.readOutbound();
            assertNotNull(encoded);
            encoded.release();
        }
    }

    @Test
    void ambiguousPacketsRequireAnExplicitKey() throws Exception {
        var entries = new ArrayList<>(registry().entries().values());
        Key second = new Key(State.HANDSHAKING, Flow.SERVERBOUND, 7);
        entries.add(new PacketRegistry.Entry<>(second, "other", NumberPacket.class, CODEC));
        try (var connection = new Connection(new PacketRegistry(entries), SessionRole.CLIENT)) {
            assertThrows(
                    CompletionException.class,
                    () -> connection.session.send(new NumberPacket(2)).join());
            connection.session.send(second, new NumberPacket(2)).join();
            ByteBuf encoded = connection.channel.readOutbound();
            assertArrayEquals(new byte[] {2, 7, 2}, StreamCodecTest.bytes(encoded));
            encoded.release();
        }
    }

    @Test
    void connectedCommandsApplyBeforeReadinessIsReported() throws Exception {
        var channel = new EmbeddedChannel(false, false);
        var session = new Session(channel, registry(), SessionRole.CLIENT, Duration.ZERO);
        session.addListener(new SessionListener() {
            @Override
            public void connected(Session connected) {
                connected.setStates(State.LOGIN, State.LOGIN);
                connected.setCompressionThreshold(16);
            }
        });
        var observed = session.ready()
                .thenApply(ready -> List.of(ready.inboundState(), ready.outboundState(), ready.compressionThreshold()));
        try {
            channel.register();
            assertEquals(List.of(State.LOGIN, State.LOGIN, 16), observed.join());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void localCodecFailuresReleaseTheBufferAndLeaveTheConnectionUsable() throws Exception {
        var allocated = new ArrayList<ByteBuf>();
        var codec = new PacketCodec<NumberPacket>() {
            public NumberPacket decode(ByteBuf input) {
                return CODEC.decode(input);
            }

            public void encode(ByteBuf output, NumberPacket value) {
                if (value.number() == 1) {
                    allocated.add(output);
                    output.writeByte(7);
                    throw new IllegalArgumentException("invalid local value");
                }
                CODEC.encode(output, value);
            }
        };
        var registry = new PacketRegistry(List.of(new PacketRegistry.Entry<>(
                new Key(State.HANDSHAKING, Flow.SERVERBOUND, 0), "number", NumberPacket.class, codec)));
        try (var connection = new Connection(registry, SessionRole.CLIENT)) {
            assertThrows(
                    CompletionException.class,
                    () -> connection
                            .session
                            .sendAndSetCompression(new NumberPacket(1), 0)
                            .join());
            assertEquals(0, allocated.getFirst().refCnt());
            assertEquals(-1, connection.session.compressionThreshold());
            assertNull(connection.channel.readOutbound());
            connection.session.send(new NumberPacket(2)).join();
            ByteBuf output = connection.channel.readOutbound();
            assertArrayEquals(new byte[] {2, 0, 2}, StreamCodecTest.bytes(output));
            output.release();
            assertTrue(connection.session.isOpen());
        }
    }

    @Test
    void removingSubscriptionsStopsCallbacksAndNormalCloseHasNoCause() throws Exception {
        var received = new ArrayList<Packet>();
        var causes = new ArrayList<Throwable>();
        try (var connection = new Connection(SessionRole.SERVER, new SessionListener() {
            @Override
            public void disconnected(Session session, Throwable cause) {
                causes.add(cause);
            }
        })) {
            var subscription = connection.session.onPacket(received::add);
            connection.channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 0, 1}));
            subscription.close();
            connection.channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 0, 2}));
            assertEquals(List.of(new NumberPacket(1)), received);
            connection.session.closeAsync().join();
            assertEquals(1, causes.size());
            assertNull(causes.getFirst());
            assertThrows(
                    CompletionException.class,
                    () -> connection.session.send(new NumberPacket(3)).join());
        }
    }

    @Test
    void skippedExceptionFromAListenerIsNotARecoverableSourceRejection() throws Exception {
        var skipped = new ArrayList<Throwable>();
        try (var connection = new Connection(SessionRole.SERVER, new SessionListener() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                throw new SkippedPacketException("listener failed");
            }

            @Override
            public void packetSkipped(Session session, SkippedPacketException cause) {
                skipped.add(cause);
            }
        })) {
            connection.channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 0, 1}));
            assertFalse(connection.session.isOpen());
            assertTrue(skipped.isEmpty());
        }
    }

    @Test
    void packetListenerFailuresCloseTheSession() throws Exception {
        var causes = new ArrayList<Throwable>();
        try (var connection = new Connection(SessionRole.SERVER, new SessionListener() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                throw new IllegalStateException("callback failed");
            }

            @Override
            public void disconnected(Session session, Throwable cause) {
                causes.add(cause);
            }
        })) {
            connection.channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 0, 1}));
            assertFalse(connection.session.isOpen());
            assertEquals("callback failed", causes.getFirst().getMessage());
        }
    }
}
