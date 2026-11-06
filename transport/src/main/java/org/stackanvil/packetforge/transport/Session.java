package org.stackanvil.packetforge.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry;
import org.stackanvil.packetforge.commons.PacketRegistry.Key;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.commons.SkippedPacketException;

/**
 * One TCP connection. Operations and listeners run in order on its event loop. Listener callbacks
 * must not block. Inbound and outbound states start at {@link State#HANDSHAKING}.
 */
public final class Session implements AutoCloseable {
    private final Channel channel;
    private final PacketRegistry registry;
    private final SessionRole role;
    private final StreamSettings settings = new StreamSettings();
    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Runnable> commands = new ArrayDeque<>();
    private final CompletableFuture<Session> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile State inboundState = State.HANDSHAKING;
    private volatile State outboundState = State.HANDSHAKING;
    private boolean draining;
    private boolean disconnected;
    private Throwable failure;

    Session(Channel channel, PacketRegistry registry, SessionRole role, Duration readTimeout) {
        this.channel = Objects.requireNonNull(channel);
        this.registry = Objects.requireNonNull(registry);
        this.role = Objects.requireNonNull(role);
        var pipeline = channel.pipeline();
        if (!readTimeout.isZero())
            pipeline.addLast("readTimeout", new ReadTimeoutHandler(readTimeout.toNanos(), TimeUnit.NANOSECONDS));
        pipeline.addLast("cipherDecoder", new CipherDecoder(settings));
        pipeline.addLast("cipherEncoder", new CipherEncoder(settings));
        pipeline.addLast("frameDecoder", new FrameDecoder());
        pipeline.addLast("frameEncoder", new FrameEncoder());
        pipeline.addLast("compressionDecoder", new CompressionDecoder(settings));
        pipeline.addLast("compressionEncoder", new CompressionEncoder(settings));
        pipeline.addLast("session", new Handler());
        channel.closeFuture().addListener(ignored -> serial(this::finishDisconnected));
    }

    public PacketRegistry registry() {
        return registry;
    }

    public SessionRole role() {
        return role;
    }

    public State inboundState() {
        return inboundState;
    }

    public State outboundState() {
        return outboundState;
    }

    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    public boolean isOpen() {
        return channel.isActive();
    }

    public int compressionThreshold() {
        return settings.threshold;
    }

    public boolean isEncrypted() {
        return settings.ciphers != null;
    }

    /** Removing a subscription does not interrupt a callback already in progress. */
    public AutoCloseable addListener(SessionListener listener) {
        Objects.requireNonNull(listener);
        listeners.addIfAbsent(listener);
        return () -> listeners.remove(listener);
    }

    public AutoCloseable onPacket(Consumer<Packet> consumer) {
        Objects.requireNonNull(consumer);
        return addListener(new SessionListener() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                consumer.accept(packet);
            }
        });
    }

    /** Selects the packet ID from the current outbound state and this endpoint's direction. */
    public CompletableFuture<Void> send(Packet packet) {
        return sendPacket(null, packet, () -> {});
    }

    /** Selects an explicit ID, with the same state, direction, and type validation as {@link #send(Packet)}. */
    public CompletableFuture<Void> send(Key key, Packet packet) {
        Objects.requireNonNull(key);
        return sendPacket(key, packet, () -> {});
    }

    /** Writes in the old state, then changes the state used by subsequent sends. */
    public CompletableFuture<Void> sendAndTransition(Packet packet, State nextOutbound) {
        Objects.requireNonNull(nextOutbound);
        return sendPacket(null, packet, () -> changeStates(inboundState, nextOutbound));
    }

    /** Writes the negotiation packet with the old compression setting, then changes both directions. */
    public CompletableFuture<Void> sendAndSetCompression(Packet packet, int threshold) {
        return sendPacket(null, packet, () -> settings.threshold = Math.max(-1, threshold));
    }

    /** Writes the key response in plaintext, then enables AES/CFB8 for subsequent stream bytes. */
    public CompletableFuture<Void> sendAndEnableEncryption(Packet packet, SecretKey key) {
        Objects.requireNonNull(packet);
        Objects.requireNonNull(key);
        return command(result -> {
            var ciphers = settings.prepare(key);
            write(null, packet, () -> settings.ciphers = ciphers, result);
        });
    }

    public CompletableFuture<Void> setInboundState(State state) {
        Objects.requireNonNull(state);
        return command(result -> {
            requireOpen();
            changeStates(state, outboundState);
            result.complete(null);
        });
    }

    public CompletableFuture<Void> setOutboundState(State state) {
        Objects.requireNonNull(state);
        return command(result -> {
            requireOpen();
            changeStates(inboundState, state);
            result.complete(null);
        });
    }

    public CompletableFuture<Void> setStates(State inbound, State outbound) {
        Objects.requireNonNull(inbound);
        Objects.requireNonNull(outbound);
        return command(result -> {
            requireOpen();
            changeStates(inbound, outbound);
            result.complete(null);
        });
    }

    /** A negative threshold disables compression; zero compresses every packet. */
    public CompletableFuture<Void> setCompressionThreshold(int threshold) {
        return command(result -> {
            requireOpen();
            settings.threshold = Math.max(-1, threshold);
            result.complete(null);
        });
    }

    public CompletableFuture<Void> enableEncryption(SecretKey key) {
        Objects.requireNonNull(key);
        return command(result -> {
            requireOpen();
            settings.ciphers = settings.prepare(key);
            result.complete(null);
        });
    }

    /** Runs application work in connection order. The action must not block the event loop. */
    public CompletableFuture<Void> execute(Runnable action) {
        Objects.requireNonNull(action);
        return command(result -> {
            requireOpen();
            action.run();
            result.complete(null);
        });
    }

    /** Completes after disconnect callbacks. An explicit close has a null disconnect cause. */
    public CompletableFuture<Void> closeFuture() {
        return closed.copy();
    }

    public CompletableFuture<Void> closeAsync() {
        command(result -> {
            channel.close();
            result.complete(null);
        });
        return closeFuture();
    }

    /** Closes with a cause reported to disconnect listeners. */
    public CompletableFuture<Void> disconnect(Throwable cause) {
        Objects.requireNonNull(cause);
        command(result -> {
            abort(cause);
            result.complete(null);
        });
        return closeFuture();
    }

    @Override
    public void close() {
        closeAsync();
    }

    CompletableFuture<Session> ready() {
        return ready;
    }

    private CompletableFuture<Void> sendPacket(Key key, Packet packet, Runnable afterWrite) {
        Objects.requireNonNull(packet);
        return command(result -> write(key, packet, afterWrite, result));
    }

    private void write(Key key, Packet packet, Runnable afterWrite, CompletableFuture<Void> result) throws Exception {
        requireOpen();
        if (!channel.isActive()) throw new IllegalStateException("Connection is not active");
        Key selected = key == null ? select(packet) : key;
        if (selected.state() != outboundState || selected.flow() != role.outbound())
            throw new IllegalArgumentException(
                    "Packet key does not match the outbound state and direction: " + selected);
        ByteBuf output = channel.alloc().buffer(256, StreamSettings.MAX_PACKET);
        try {
            registry.encode(selected, packet, output);
        } catch (Throwable invalid) {
            output.release();
            throw invalid;
        }
        var written = channel.writeAndFlush(output);
        // The write traverses compression and encryption synchronously on this event loop.
        // Queue reentrant sends until the boundary and its completion listener are installed.
        try {
            afterWrite.run();
        } catch (Throwable invalid) {
            abort(invalid);
            throw invalid;
        }
        written.addListener(done -> serial(() -> {
            if (!done.isSuccess()) {
                abort(done.cause());
                result.completeExceptionally(done.cause());
                return;
            }
            try {
                notifyListeners(listener -> listener.packetSent(this, packet));
                result.complete(null);
            } catch (Throwable invalid) {
                result.completeExceptionally(invalid);
            }
        }));
    }

    private Key select(Packet packet) {
        Key selected = null;
        for (var entry : registry.entries().values()) {
            Key key = entry.key();
            if (key.state() != outboundState
                    || key.flow() != role.outbound()
                    || !entry.type().isInstance(packet)) continue;
            if (selected != null)
                throw new IllegalArgumentException("Ambiguous packet type; send with an explicit registry key");
            selected = key;
        }
        if (selected == null)
            throw new IllegalArgumentException(
                    "No outbound packet codec for " + packet.getClass().getName() + " in " + outboundState);
        return selected;
    }

    private void changeStates(State inbound, State outbound) {
        if (inboundState == inbound && outboundState == outbound) return;
        inboundState = inbound;
        outboundState = outbound;
        notifyListeners(listener -> listener.stateChanged(this, inbound, outbound));
    }

    private void requireOpen() throws ClosedChannelException {
        if (!channel.isOpen()) throw new ClosedChannelException();
    }

    @FunctionalInterface
    private interface Command {
        void run(CompletableFuture<Void> result) throws Exception;
    }

    private CompletableFuture<Void> command(Command command) {
        var result = new CompletableFuture<Void>();
        Runnable action = () -> {
            try {
                command.run(result);
            } catch (Throwable invalid) {
                result.completeExceptionally(invalid);
            }
        };
        try {
            if (channel.eventLoop().inEventLoop()) serial(action);
            else channel.eventLoop().execute(() -> serial(action));
        } catch (Throwable rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private void serial(Runnable action) {
        commands.add(action);
        if (draining) return;
        draining = true;
        try {
            while (!commands.isEmpty()) commands.remove().run();
        } finally {
            draining = false;
        }
    }

    private void notifyListeners(Consumer<SessionListener> callback) {
        try {
            for (var listener : listeners) callback.accept(listener);
        } catch (Throwable invalid) {
            abort(invalid);
            throw invalid;
        }
    }

    private void abort(Throwable cause) {
        if (failure == null) failure = cause;
        ready.completeExceptionally(cause);
        channel.close();
    }

    private void finishDisconnected() {
        if (disconnected) return;
        disconnected = true;
        ready.completeExceptionally(failure == null ? new ClosedChannelException() : failure);
        for (var listener : listeners) {
            try {
                listener.disconnected(this, failure);
            } catch (Throwable ignored) {
                /* One listener must not prevent the remaining disconnect callbacks. */
            }
        }
        closed.complete(null);
    }

    private final class Handler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        public void channelActive(ChannelHandlerContext context) {
            serial(() -> {
                try {
                    notifyListeners(listener -> listener.connected(Session.this));
                    commands.add(() -> {
                        if (channel.isActive()) ready.complete(Session.this);
                        else ready.completeExceptionally(failure == null ? new ClosedChannelException() : failure);
                    });
                } catch (Throwable ignored) {
                    /* notifyListeners closes the connection on failure. */
                }
            });
            context.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf input) {
            ByteBuf body = input.retainedDuplicate();
            serial(() -> {
                try {
                    if (disconnected || !channel.isActive()) return;
                    Packet packet;
                    try {
                        packet = registry.decode(inboundState, role.inbound(), body);
                    } catch (SkippedPacketException skipped) {
                        notifyListeners(listener -> listener.packetSkipped(Session.this, skipped));
                        return;
                    }
                    notifyListeners(listener -> listener.packetReceived(Session.this, packet));
                } catch (Throwable invalid) {
                    abort(invalid);
                } finally {
                    body.release();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            serial(Session.this::finishDisconnected);
            context.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            serial(() -> abort(cause));
        }
    }
}
