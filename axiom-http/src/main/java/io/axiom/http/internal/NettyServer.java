package io.axiom.http.internal;

import io.axiom.application.Application;
import io.axiom.execution.AdmissionPolicy;
import io.axiom.execution.AdmissionSnapshot;
import io.axiom.lifecycle.Server;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class NettyServer implements Server {
    /** How long close() lets in-flight exchanges finish before interrupting them. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private final MultiThreadIoEventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(
            1, Thread.ofPlatform().name("axiom-http-accept-", 0).factory(), NioIoHandler.newFactory());
    private final MultiThreadIoEventLoopGroup io = new MultiThreadIoEventLoopGroup(
            2, Thread.ofPlatform().name("axiom-http-io-", 0).factory(), NioIoHandler.newFactory());
    private final DefaultChannelGroup channels = new DefaultChannelGroup(io.next(), true);
    private final RequestDispatcher handlers;
    private final CompletableFuture<Void> stopped;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger connections = new AtomicInteger();
    private final Duration grace;
    private Channel listener;
    private InetSocketAddress address;

    private NettyServer(AdmissionPolicy policy, Duration grace) {
        this.grace = grace;
        handlers = new RequestDispatcher(policy);
        stopped = CompletableFuture.allOf(handlers.termination().toCompletableFuture(),
                completion(acceptors.terminationFuture()), completion(io.terminationFuture()));
    }

    static NettyServer bind(Application application, InetSocketAddress address) throws IOException {
        return bind(application, address, SHUTDOWN_GRACE);
    }

    static NettyServer bind(Application application, InetSocketAddress address, Duration grace) throws IOException {
        var server = new NettyServer(application.admissionPolicy(), grace);
        try {
            var bootstrap = new ServerBootstrap().group(server.acceptors, server.io)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            server.channels.add(channel);
                            // close() sets the flag before draining the group, so a racing accept closes itself.
                            if (server.closing.get()) { channel.close(); return; }
                            if (server.connections.incrementAndGet() > 128) {
                                server.connections.decrementAndGet();
                                channel.close();
                                return;
                            }
                            channel.closeFuture().addListener(ignored -> server.connections.decrementAndGet());
                            var config = new HttpDecoderConfig().setMaxInitialLineLength(4096)
                                    .setMaxHeaderSize(8192).setMaxChunkSize(8192)
                                    .setValidateHeaders(true).setAllowDuplicateContentLengths(false)
                                    .setStrictLineParsing(true).setUseRfc9112TransferEncoding(true);
                            channel.pipeline().addLast(new IdleStateHandler(0, 0, 30),
                                    new HttpServerCodec(config), new HttpConnection(application, server.handlers));
                        }
                    });
            var bound = bootstrap.bind(address).awaitUninterruptibly();
            if (!bound.isSuccess()) { throw new IOException("Could not bind HTTP listener to " + address, bound.cause()); }
            server.listener = bound.channel();
            server.address = (InetSocketAddress) server.listener.localAddress();
            return server;
        } catch (IOException | RuntimeException | Error failure) {
            server.close();
            server.stopped.join();
            throw failure;
        }
    }

    @Override public AdmissionSnapshot admission() { return handlers.snapshot(); }
    @Override public InetSocketAddress localAddress() { return address; }
    @Override public boolean isOpen() { return !closing.get() && listener != null && listener.isOpen(); }
    @Override public CompletionStage<Void> termination() { return stopped.minimalCompletionStage(); }

    /**
     * Stops accepting, lets in-flight exchanges finish and closes their connections after the
     * response; idle connections close at once. After the grace period, remaining connections
     * are closed and their handlers interrupted. Then execution and event loops stop.
     */
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) { return; }
        if (listener == null) { stop(); return; }
        listener.close().addListener(ignored -> {
            var drained = channels.newCloseFuture();
            for (var channel : channels) {
                channel.eventLoop().execute(() -> {
                    var connection = channel.pipeline().get(HttpConnection.class);
                    if (connection == null) { channel.close(); } else { connection.drain(); }
                });
            }
            var force = acceptors.next().schedule(() -> { channels.close(); }, grace.toNanos(), TimeUnit.NANOSECONDS);
            drained.addListener(done -> { force.cancel(false); stop(); });
        });
    }

    private void stop() {
        handlers.close();
        acceptors.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        io.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Void> completion(io.netty.util.concurrent.Future<?> future) {
        var result = new CompletableFuture<Void>();
        future.addListener(done -> {
            if (done.isSuccess()) { result.complete(null); }
            else { result.completeExceptionally(done.cause()); }
        });
        return result;
    }
}
