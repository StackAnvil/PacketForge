package org.stackanvil.packetforge.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.stackanvil.packetforge.commons.PacketRegistry;
import org.stackanvil.packetforge.protocol.GeneratedProtocol;

/** Owns TCP event loops and the connections opened through them. Close it when no longer needed. */
public final class TcpTransport implements AutoCloseable {
    private final PacketRegistry registry;
    private final MultiThreadIoEventLoopGroup loops;
    private final DefaultChannelGroup channels;
    private final int connectTimeoutMillis;
    private final Duration readTimeout;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    public TcpTransport() {
        this(GeneratedProtocol.registry());
    }

    public TcpTransport(PacketRegistry registry) {
        this(registry, 1, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    /** A zero read timeout disables idle-read detection. Connection timeouts must be positive. */
    public TcpTransport(PacketRegistry registry, int threads, Duration connectTimeout, Duration readTimeout) {
        this.registry = Objects.requireNonNull(registry);
        Objects.requireNonNull(connectTimeout);
        this.readTimeout = Objects.requireNonNull(readTimeout);
        if (threads < 1) throw new IllegalArgumentException("At least one network thread is required");
        if (connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException(
                    "Connection timeout must be positive and fit an integer in milliseconds");
        if (readTimeout.isNegative()) throw new IllegalArgumentException("Negative read timeout");
        readTimeout.toNanos();
        connectTimeoutMillis = (int) Math.max(1, connectTimeout.toMillis());
        loops = new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
        channels = new DefaultChannelGroup(loops.next(), true);
    }

    public CompletableFuture<Session> connect(SocketAddress address) {
        return connect(address, ignored -> {});
    }

    /** Configure listeners before the session becomes active. The future completes after connected callbacks. */
    public CompletableFuture<Session> connect(SocketAddress address, Consumer<Session> configure) {
        Objects.requireNonNull(address);
        Objects.requireNonNull(configure);
        if (closing.get()) return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
        var result = new CompletableFuture<Session>();
        var session = new AtomicReference<Session>();
        var bootstrap = new Bootstrap()
                .group(loops)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channels.add(channel);
                        if (!channel.isOpen()) return;
                        var connection = new Session(channel, registry, SessionRole.CLIENT, readTimeout);
                        session.set(connection);
                        try {
                            configure.accept(connection);
                        } catch (Throwable invalid) {
                            result.completeExceptionally(invalid);
                            connection.disconnect(invalid);
                        }
                    }
                });
        try {
            var connecting = bootstrap.connect(address);
            channels.add(connecting.channel());
            result.whenComplete((value, cause) -> {
                if (cause != null) connecting.channel().close();
            });
            connecting.addListener(done -> {
                if (!done.isSuccess()) {
                    result.completeExceptionally(done.cause());
                    return;
                }
                Session connection = session.get();
                if (connection == null) {
                    result.completeExceptionally(new IllegalStateException("Connection was not initialized"));
                    return;
                }
                connection.ready().whenComplete((value, cause) -> {
                    if (cause != null) result.completeExceptionally(cause);
                    else if (!result.complete(value)) value.close();
                });
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public CompletableFuture<TcpServer> bind(SocketAddress address, Consumer<Session> configure) {
        Objects.requireNonNull(address);
        Objects.requireNonNull(configure);
        if (closing.get()) return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
        var result = new CompletableFuture<TcpServer>();
        var serverChannels = new DefaultChannelGroup(loops.next(), true);
        var bootstrap = new ServerBootstrap()
                .group(loops)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channels.add(channel);
                        serverChannels.add(channel);
                        if (!channel.isOpen()) return;
                        var connection = new Session(channel, registry, SessionRole.SERVER, readTimeout);
                        try {
                            configure.accept(connection);
                        } catch (Throwable invalid) {
                            connection.disconnect(invalid);
                        }
                    }
                });
        try {
            var binding = bootstrap.bind(address);
            channels.add(binding.channel());
            serverChannels.add(binding.channel());
            result.whenComplete((value, cause) -> {
                if (cause != null) serverChannels.close();
            });
            binding.addListener(done -> {
                if (!done.isSuccess()) {
                    result.completeExceptionally(done.cause());
                    return;
                }
                var server = new TcpServer(this, binding.channel(), serverChannels);
                if (!result.complete(server)) server.closeAsync();
            });
        } catch (Throwable failure) {
            serverChannels.close();
            result.completeExceptionally(failure);
        }
        return result;
    }

    public CompletableFuture<Void> closeAsync() {
        if (closing.compareAndSet(false, true)) {
            channels.close().addListener(connectionsClosed -> {
                loops.shutdownGracefully(0, 5, TimeUnit.SECONDS).addListener(stopped -> {
                    if (!connectionsClosed.isSuccess()) closed.completeExceptionally(connectionsClosed.cause());
                    else if (!stopped.isSuccess()) closed.completeExceptionally(stopped.cause());
                    else closed.complete(null);
                });
            });
        }
        return closed.copy();
    }

    /** Waits outside network callbacks. Inside a callback it starts shutdown without blocking the event loop. */
    @Override
    public void close() {
        var future = closeAsync();
        if (!inEventLoop()) future.join();
    }

    boolean inEventLoop() {
        for (var executor : loops) if (executor.inEventLoop()) return true;
        return false;
    }
}
