package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.lifecycle.TlsConfigurationException;
import com.jsgalactic.axiom.lifecycle.TransportKind;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.server.internal.ExecutionAccess;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import com.jsgalactic.axiom.server.internal.execution.StreamMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
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
    private final MultiThreadIoEventLoopGroup acceptors;
    final MultiThreadIoEventLoopGroup io;
    private final DefaultChannelGroup channels;
    private final RequestDispatcher handlers;
    private final RequestDispatcher.Session session;
    private final CompletableFuture<Void> stopped;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ConnectionSlots slots;
    private final BodyBudget bodyBudget;
    private final StreamMetrics streamMetrics;
    private final ListenerMetrics listenerMetrics;
    private final TransportSettings settings;
    private final HttpDecoderConfig decoderConfig;
    private static final System.Logger LOG = System.getLogger(NettyServer.class.getName());
    private final TlsMetrics tlsMetrics;
    /** The key material new connections use; replaced as a whole by a reload. Null for plain HTTP. */
    private volatile TlsMaterial tls;
    private final Object reloadLock = new Object();
    private String failedFingerprint;
    private ScheduledFuture<?> tlsPoll;
    Channel listener;
    private InetSocketAddress address;

    private final Transport transport;

    private NettyServer(RequestDispatcher handlers, RequestDispatcher.Session session, Metrics metrics,
            TransportSettings settings, TlsMaterial tls, Transport transport) {
        this.settings = settings;
        this.transport = transport;
        this.handlers = handlers;
        this.session = session;
        acceptors = new MultiThreadIoEventLoopGroup(
                1, Thread.ofPlatform().name("axiom-http-accept-", 0).factory(), transport.handlers().get());
        this.tls = tls;
        this.tlsMetrics = new TlsMetrics(metrics);
        var options = settings.options();
        listenerMetrics = metrics == Metrics.NOOP ? ListenerMetrics.DISABLED : new ListenerMetrics(metrics, options.name());
        slots = new ConnectionSlots(options.maxConnections(), options.maxLingeringConnections(), metrics, options.name());
        decoderConfig = decoderConfig(options);
        bodyBudget = new BodyBudget(options.maxInFlightBodyBytes());
        io = new MultiThreadIoEventLoopGroup(options.ioThreads(),
                Thread.ofPlatform().name("axiom-http-io-", 0).factory(), transport.handlers().get());
        channels = new DefaultChannelGroup(io.next(), true);
        streamMetrics = new StreamMetrics(metrics);
        stopped = CompletableFuture.allOf(session.termination().toCompletableFuture(),
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
        // Invalid key material stops startup before any thread or socket exists.
        var tlsOptions = settings.options().tls();
        var material = tlsOptions.isPresent() ? TlsMaterial.load(tlsOptions.get()) : null;
        // An unusable explicitly requested transport also fails before any thread or socket exists.
        var transport = Transport.resolve(settings.options().transport());
        var execution = ExecutionAccess.of(application).execution();
        var session = execution.openSession();
        var server = new NettyServer(execution, session, application.metrics(), settings, material, transport);
        try {
            var bootstrap = new ServerBootstrap().group(server.acceptors, server.io)
                    .channel(transport.serverChannel())
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
            server.startTlsPolling();
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
        if (closing.get()) { listenerMetrics.refused(ListenerMetrics.Refusal.SHUTDOWN); channel.close(); return; }
        var slot = slots.acquire();
        if (slot == null) {
            listenerMetrics.refused(ListenerMetrics.Refusal.LIMIT);
            channel.close();
            return;
        }
        listenerMetrics.accepted();
        // First in the pipeline, so it counts what crosses the socket, encrypted on a TLS listener.
        if (listenerMetrics.enabled()) { channel.pipeline().addLast(new ByteCounter(listenerMetrics)); }
        // The slot is released exactly once, by this listener, whether or not setup succeeds.
        channel.closeFuture().addListener(ignored -> slot.release());
        var material = tls;
        if (material != null) {
            try {
                var ssl = new SslHandler(material.newEngine(settings.options().http2Enabled()));
                ssl.setHandshakeTimeoutMillis(settings.options().handshakeTimeout().toMillis());
                channel.pipeline().addLast(ssl, new TlsHandshakeHandler(tlsMetrics));
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "Could not start TLS on a connection; it was closed", failure);
                channel.close();
                return;
            }
        }
        channel.pipeline().addLast(new IdleStateHandler(0, 0, settings.idleTimeout().toNanos(), TimeUnit.NANOSECONDS));
        if (material != null && settings.options().http2Enabled()) {
            channel.pipeline().addLast(new Http2AlpnHandler(application, slot));
        } else if (material == null && settings.options().http2Enabled()) {
            channel.pipeline().addLast(new Http2PrefaceDetector(
                    () -> configureHttp1(channel, application, slot), () -> configureHttp2(channel, application, slot)));
        } else {
            configureHttp1(channel, application, slot);
        }
    }

    /** Installs the HTTP/1.x codec and the same connection handler both protocols use. */
    private void configureHttp1(Channel channel, Application application, ConnectionSlots.Slot slot) {
        channel.pipeline().addLast(new RequestDecoder(decoderConfig), new HttpResponseEncoder(),
                new HttpConnection(application, handlers, session, settings, slot, closing::get, bodyBudget,
                        streamMetrics, listenerMetrics));
    }

    /**
     * Installs the HTTP/2 connection codec, one child channel per stream, and a handler that
     * converts frames to the same HTTP objects the HTTP/1 connection handler consumes. Each stream
     * shares the listener's slot, body budget, metrics, session and dispatcher with its siblings.
     */
    private void configureHttp2(Channel channel, Application application, ConnectionSlots.Slot slot) {
        var options = settings.options();
        var streams = new Http2Streams(channel);
        var initial = new Http2Settings().maxConcurrentStreams(options.maxPipelinedRequests())
                .maxHeaderListSize(options.maxHeaderBytes());
        channel.pipeline().addLast(Http2FrameCodecBuilder.forServer().initialSettings(initial).build(),
                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel stream) {
                        stream.config().setWriteBufferWaterMark(WATER_MARK);
                        channels.add(stream);
                        streams.opened(stream);
                        stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true), new Http2StreamBridge(),
                                new HttpConnection(application, handlers, session, settings, slot, closing::get,
                                        bodyBudget, streamMetrics, listenerMetrics, true));
                    }
                }), new Http2GracefulCloseHandler(streams));
    }

    /**
     * Chooses the wire protocol a TLS client selected with ALPN, defaulting to HTTP/1.1. Application
     * data is held until the handshake completes, so neither codec sees bytes before its pipeline
     * is in place. The negotiated name comes from the JDK engine, which Netty's {@code SslHandler}
     * does not expose through its own ALPN accessor when given a raw engine.
     */
    private final class Http2AlpnHandler extends ChannelInboundHandlerAdapter {
        private final Application application;
        private final ConnectionSlots.Slot slot;
        private final List<Object> buffered = new ArrayList<>();
        private boolean configured;

        Http2AlpnHandler(Application application, ConnectionSlots.Slot slot) {
            this.application = application;
            this.slot = slot;
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (configured) { ctx.fireChannelRead(message); }
            else { buffered.add(message); }
        }

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof SslHandshakeCompletionEvent handshake && handshake.isSuccess() && !configured) {
                configured = true;
                var ssl = ctx.pipeline().get(SslHandler.class);
                var protocol = ssl == null ? null : ssl.engine().getApplicationProtocol();
                if (TlsMaterial.ALPN_HTTP2.equals(protocol)) { configureHttp2(ctx.channel(), application, slot); }
                else { configureHttp1(ctx.channel(), application, slot); }
                ctx.pipeline().remove(this);
                for (var message : buffered) { ctx.fireChannelRead(message); }
                buffered.clear();
                ctx.fireChannelReadComplete();
            }
            super.userEventTriggered(ctx, event);
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            buffered.forEach(ReferenceCountUtil::release);
            buffered.clear();
            super.channelInactive(ctx);
        }
    }

    /** Request line and header bounds (414 and 431 beyond them) and strict framing rules. */
    static HttpDecoderConfig decoderConfig() { return decoderConfig(ListenerOptions.defaults()); }

    static HttpDecoderConfig decoderConfig(ListenerOptions options) {
        return new HttpDecoderConfig().setMaxInitialLineLength(options.maxRequestLine())
                .setMaxHeaderSize(options.maxHeaderBytes()).setMaxChunkSize(8192)
                .setValidateHeaders(true).setAllowDuplicateContentLengths(false)
                .setStrictLineParsing(true).setUseRfc9112TransferEncoding(true);
    }

    @Override public void reloadTls() throws IOException {
        var options = settings.options().tls()
                .orElseThrow(() -> new IllegalStateException("This listener does not serve TLS"));
        synchronized (reloadLock) {
            try {
                tls = TlsMaterial.load(options);
                failedFingerprint = null;
                tlsMetrics.reload(TlsMetrics.Reload.COMPLETED);
            } catch (TlsConfigurationException rejected) {
                tlsMetrics.reload(TlsMetrics.Reload.FAILED);
                throw rejected;
            }
        }
    }

    private void startTlsPolling() {
        var interval = settings.options().tls().flatMap(options -> options.reloadInterval());
        if (interval.isEmpty()) { return; }
        long millis = interval.get().toMillis();
        // Parsing and key generation do not belong on the acceptor thread.
        tlsPoll = acceptors.next().scheduleWithFixedDelay(
                () -> Thread.ofVirtual().name("axiom-tls-reload").start(this::pollTls), millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Reloads the key material when its files changed since they were last loaded. A rejected
     * change is logged and counted once per distinct content and retried when the files change
     * again; the previous material stays in use. For the polling task and tests.
     */
    void pollTls() {
        var options = settings.options().tls();
        if (options.isEmpty() || closing.get()) { return; }
        synchronized (reloadLock) {
            var current = TlsMaterial.currentFingerprint(options.get());
            if (current == null || current.equals(tls.fingerprint()) || current.equals(failedFingerprint)) { return; }
            try {
                tls = TlsMaterial.load(options.get());
                tlsMetrics.reload(TlsMetrics.Reload.COMPLETED);
            } catch (TlsConfigurationException rejected) {
                failedFingerprint = current;
                tlsMetrics.reload(TlsMetrics.Reload.FAILED);
                LOG.log(System.Logger.Level.WARNING, "TLS reload rejected, keeping the previous material: "
                        + rejected.getMessage());
            }
        }
    }

    @Override public AdmissionSnapshot admission() { return handlers.snapshot(); }
    /** The bounds this listener applies; for tests. */
    TransportSettings settings() { return settings; }
    /** The I/O mechanism this listener runs on, never AUTO; for tests. */
    TransportKind transport() { return transport.kind(); }
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
        session.stop();
        if (tlsPoll != null) { tlsPoll.cancel(false); }
        if (listener == null) { stop(); return; }
        // Accepts racing with this see the flag and close themselves (see accept).
        var drained = channels.newCloseFuture();
        for (var channel : channels) {
            channel.eventLoop().execute(() -> {
                var connection = channel.pipeline().get(HttpConnection.class);
                var h2 = channel.pipeline().get(Http2GracefulCloseHandler.class);
                if (h2 != null) { h2.goAway(); }
                else if (connection == null) { channel.close(); }
                else { connection.drain(); }
            });
        }
        listener.close().addListener(ignored -> {
            var force = acceptors.next().schedule(() -> { channels.close(); }, settings.shutdownGrace().toNanos(), TimeUnit.NANOSECONDS);
            drained.addListener(done -> { force.cancel(false); stop(); });
        });
    }

    private void stop() {
        session.close();
        acceptors.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        io.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Void> completion(Future<?> future) {
        var result = new CompletableFuture<Void>();
        future.addListener(done -> {
            if (done.isSuccess()) { result.complete(null); }
            else { result.completeExceptionally(done.cause()); }
        });
        return result;
    }
}
