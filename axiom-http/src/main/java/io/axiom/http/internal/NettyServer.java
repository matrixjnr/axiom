package io.axiom.http.internal;

import io.axiom.application.Application;
import io.axiom.lifecycle.Server;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class NettyServer implements Server {
    private final MultiThreadIoEventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(
            1, Thread.ofPlatform().name("axiom-http-accept-", 0).factory(), NioIoHandler.newFactory());
    private final MultiThreadIoEventLoopGroup io = new MultiThreadIoEventLoopGroup(
            2, Thread.ofPlatform().name("axiom-http-io-", 0).factory(), NioIoHandler.newFactory());
    private final DefaultChannelGroup channels = new DefaultChannelGroup(io.next(), true);
    private final CompletableFuture<Void> handlersStopped = new CompletableFuture<>();
    private final ThreadPoolExecutor handlers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("axiom-http-handler-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()) {
        @Override protected void terminated() { handlersStopped.complete(null); }
    };
    private final CompletableFuture<Void> stopped = CompletableFuture.allOf(
            handlersStopped, completion(acceptors.terminationFuture()), completion(io.terminationFuture()));
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger connections = new AtomicInteger();
    private Channel listener;
    private InetSocketAddress address;

    static NettyServer bind(Application application, InetSocketAddress address) throws IOException {
        var server = new NettyServer();
        try {
            var bootstrap = new ServerBootstrap().group(server.acceptors, server.io)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            server.channels.add(channel);
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
            server.channels.add(server.listener);
            return server;
        } catch (IOException | RuntimeException | Error failure) {
            server.close();
            server.stopped.join();
            throw failure;
        }
    }

    @Override public InetSocketAddress localAddress() { return address; }
    @Override public boolean isOpen() { return !closing.get() && listener.isOpen(); }
    @Override public CompletionStage<Void> termination() { return stopped.minimalCompletionStage(); }

    @Override public void close() {
        if (!closing.compareAndSet(false, true)) { return; }
        channels.close().addListener(ignored -> {
            for (var task : handlers.shutdownNow()) {
                if (task instanceof Future<?> future) { future.cancel(true); }
            }
            acceptors.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            io.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        });
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
