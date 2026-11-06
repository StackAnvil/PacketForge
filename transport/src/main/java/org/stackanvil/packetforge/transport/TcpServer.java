package org.stackanvil.packetforge.transport;

import io.netty.channel.Channel;
import io.netty.channel.group.DefaultChannelGroup;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/** A listening socket and its accepted connections. Other listeners on the transport remain usable. */
public final class TcpServer implements AutoCloseable {
    private final TcpTransport transport;
    private final Channel listener;
    private final DefaultChannelGroup channels;

    TcpServer(TcpTransport transport, Channel listener, DefaultChannelGroup channels) {
        this.transport = transport;
        this.listener = listener;
        this.channels = channels;
    }

    public SocketAddress localAddress() {
        return listener.localAddress();
    }

    public boolean isOpen() {
        return listener.isActive();
    }

    public CompletableFuture<Void> closeAsync() {
        var result = new CompletableFuture<Void>();
        channels.close().addListener(done -> {
            if (done.isSuccess()) result.complete(null);
            else result.completeExceptionally(done.cause());
        });
        return result;
    }

    @Override
    public void close() {
        var future = closeAsync();
        if (!transport.inEventLoop()) future.join();
    }
}
