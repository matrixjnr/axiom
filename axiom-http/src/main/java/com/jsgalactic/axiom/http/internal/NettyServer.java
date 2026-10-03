package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NettyServer implements Server {
    // The production defaults; they live in ListenerOptions, which validates every override.
    /** How long close() lets in-flight exchanges finish before interrupting them. */
    static final Duration SHUTDOWN_GRACE = ListenerOptions.defaults().shutdownGrace();
    /**
     * Network inactivity (no read and no write progress) after which a connection closes. It also
     * catches a response write stalled by a client that stopped reading.
     */
    static final Duration IDLE_TIMEOUT = ListenerOptions.defaults().idleTimeout();
    /** Open connections per listener; further accepted connections close immediately. */
    static final int MAX_CONNECTIONS = ListenerOptions.defaults().maxConnections();
    /**
     * Connections that only linger after their last response and no longer count against
     * {@link #MAX_CONNECTIONS}. Beyond this many, a lingering connection keeps its regular slot.
     */
    static final int MAX_LINGERING = ListenerOptions.defaults().maxLingeringConnections();
    /** Pending-accept queue length requested from the operating system. */
    static final int BACKLOG = 1024;
    /** Per-connection outbound buffering at which the channel reports itself unwritable. */
    static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 128 * 1024);
    private final MultiThreadIoEventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(
            1, Thread.ofPlatform().name("axiom-http-accept-", 0).factory(), NioIoHandler.newFactory());
    final MultiThreadIoEventLoopGroup io;
    private final DefaultChannelGroup channels;
    private final RequestDispatcher handlers;
    private final CompletableFuture<Void> stopped;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ConnectionSlots slots;
    private final BodyBudget bodyBudget;
    private final TransportSettings settings;
    private final HttpDecoderConfig decoderConfig;
    Channel listener;
    private InetSocketAddress address;

    private NettyServer(AdmissionPolicy policy, Metrics metrics, TransportSettings settings) {
        this.settings = settings;
        var options = settings.options();
        slots = new ConnectionSlots(options.maxConnections(), options.maxLingeringConnections(), metrics);
        decoderConfig = decoderConfig(options);
        bodyBudget = new BodyBudget(options.maxInFlightBodyBytes());
        io = new MultiThreadIoEventLoopGroup(options.ioThreads(),
                Thread.ofPlatform().name("axiom-http-io-", 0).factory(), NioIoHandler.newFactory());
        channels = new DefaultChannelGroup(io.next(), true);
        handlers = new RequestDispatcher(policy, metrics);
        stopped = CompletableFuture.allOf(handlers.termination().toCompletableFuture(),
                completion(acceptors.terminationFuture()), completion(io.terminationFuture()));
    }

    static NettyServer bind(Application application, InetSocketAddress address) throws IOException {
        return bind(application, address, TransportSettings.DEFAULTS);
    }

    /** Binds with non-default bounds; for tests that must tolerate a slow machine or observe one bound. */
    static NettyServer bind(Application application, InetSocketAddress address, TransportSettings settings)
            throws IOException {
        // No body of the application's permitted size could ever be accepted by a smaller budget.
        if (settings.options().maxInFlightBodyBytes() < application.maxRequestBody()) {
            throw new IllegalArgumentException("maxInFlightBodyBytes (" + settings.options().maxInFlightBodyBytes()
                    + ") must be at least the application's maxRequestBody (" + application.maxRequestBody() + ")");
        }
        var server = new NettyServer(application.admissionPolicy(), application.metrics(), settings);
        try {
            var bootstrap = new ServerBootstrap().group(server.acceptors, server.io)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, BACKLOG)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);
            if (settings.receiveBuffer() > 0) {
                // Set on the listening socket too, so accepted sockets advertise the window from the start.
                bootstrap.option(ChannelOption.SO_RCVBUF, settings.receiveBuffer())
                        .childOption(ChannelOption.SO_RCVBUF, settings.receiveBuffer());
            }
            if (settings.sendBuffer() > 0) { bootstrap.childOption(ChannelOption.SO_SNDBUF, settings.sendBuffer()); }
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel channel) { server.accept(channel, application); }
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

    /** Admits an accepted connection on its event loop, or closes it when closing or full. */
    void accept(Channel channel, Application application) {
        channel.config().setWriteBufferWaterMark(WATER_MARK);
        channels.add(channel);
        // close() sets the flag before draining the group, so a racing accept closes itself.
        if (closing.get()) { channel.close(); return; }
        var slot = slots.acquire();
        if (slot == null) {
            channel.close();
            return;
        }
        // The slot is released exactly once, by this listener, whether or not setup succeeds.
        channel.closeFuture().addListener(ignored -> slot.release());
        channel.pipeline().addLast(
                new IdleStateHandler(0, 0, settings.idleTimeout().toNanos(), TimeUnit.NANOSECONDS),
                new RequestDecoder(decoderConfig), new HttpResponseEncoder(),
                new HttpConnection(application, handlers, settings, slot, closing::get, bodyBudget));
    }

    /** Request line and header bounds (414 and 431 beyond them) and strict framing rules. */
    static HttpDecoderConfig decoderConfig() { return decoderConfig(ListenerOptions.defaults()); }

    static HttpDecoderConfig decoderConfig(ListenerOptions options) {
        return new HttpDecoderConfig().setMaxInitialLineLength(options.maxRequestLine())
                .setMaxHeaderSize(options.maxHeaderBytes()).setMaxChunkSize(8192)
                .setValidateHeaders(true).setAllowDuplicateContentLengths(false)
                .setStrictLineParsing(true).setUseRfc9112TransferEncoding(true);
    }

    @Override public AdmissionSnapshot admission() { return handlers.snapshot(); }
    /** The bounds this listener applies; for tests. */
    TransportSettings settings() { return settings; }
    /** Connections holding a regular slot; for tests. */
    int connections() { return slots.open(); }
    /** Request body bytes currently reserved against this listener's budget; for tests. */
    long bodyBytesInFlight() { return bodyBudget.used(); }
    /** Lingering connections that no longer hold a regular slot; for tests. */
    int lingering() { return slots.lingering(); }
    @Override public InetSocketAddress localAddress() { return address; }
    @Override public boolean isOpen() { return !closing.get() && listener != null && listener.isOpen(); }
    @Override public CompletionStage<Void> termination() { return stopped.minimalCompletionStage(); }

    /**
     * Stops accepting and admitting; requests still waiting for capacity receive 503. Running
     * exchanges finish and their connections close after the response; idle connections close at
     * once. After the grace period, remaining connections are closed and their handlers
     * interrupted. Then execution and event loops stop.
     */
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) { return; }
        // From here on every response is sent with Connection: close (connections read the flag),
        // so the drain is in force before stopping admission answers waiting requests below. The
        // per-connection drain also runs at once rather than after the listening socket has closed,
        // which completes on the acceptor thread and may be delayed.
        handlers.stopAdmission();
        if (listener == null) { stop(); return; }
        // Accepts racing with this see the flag and close themselves (see accept).
        var drained = channels.newCloseFuture();
        for (var channel : channels) {
            channel.eventLoop().execute(() -> {
                var connection = channel.pipeline().get(HttpConnection.class);
                if (connection == null) { channel.close(); } else { connection.drain(); }
            });
        }
        listener.close().addListener(ignored -> {
            var force = acceptors.next().schedule(() -> { channels.close(); }, settings.shutdownGrace().toNanos(), TimeUnit.NANOSECONDS);
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
