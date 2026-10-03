package com.jsgalactic.axiom.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport limits and timeouts of one HTTP listener, passed to
 * {@link com.jsgalactic.axiom.application.Application#listen(java.net.InetSocketAddress, ListenerOptions)}.
 * Every setting has a secure bounded default, so {@link #defaults()} suits most deployments, and a
 * documented valid range that the {@linkplain Builder builder} enforces when a value is set.
 * Instances are immutable and may be shared by several listeners; each listener applies its own
 * copy of the limits to its own connections.
 *
 * <p>The settings are interpreted by the installed transport. They bound what a client can make a
 * listener hold or wait for; they do not change request semantics. Application-level limits stay on
 * the application: the request body limit, the execution deadline and admission. How the connection
 * cap relates to admission is described at {@link Builder#maxConnections(int)}.
 *
 * <p>Durations use the range 1 millisecond to {@value #MAX_DURATION_DAYS} day (the shutdown grace period
 * also allows zero).
 */
public final class ListenerOptions {
    /** Longest accepted duration, in days. */
    public static final int MAX_DURATION_DAYS = 1;
    private static final Duration MAX_DURATION = Duration.ofDays(MAX_DURATION_DAYS);
    private static final Duration MIN_DURATION = Duration.ofMillis(1);
    private static final ListenerOptions DEFAULTS = new Builder().build();

    /** Longest listener name. */
    public static final int MAX_NAME_LENGTH = 32;
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");

    private final String name;
    private final Duration shutdownGrace;
    private final Duration idleTimeout;
    private final Duration headTimeout;
    private final Duration responseTimeout;
    private final Duration lingerTimeout;
    private final Duration lingerQuietTimeout;
    private final Duration shutdownLingerTimeout;
    private final Duration handshakeTimeout;
    private final TlsOptions tls;
    private final int maxDiscardedInput;
    private final int maxConnections;
    private final int maxLingeringConnections;
    private final int maxPipelinedRequests;
    private final long maxInFlightBodyBytes;
    private final int maxRequestLine;
    private final int maxHeaderBytes;
    private final int ioThreads;
    private final RejectionObserver rejectionObserver;

    private ListenerOptions(Builder b) {
        rejectionObserver = b.rejectionObserver;
        name = b.name;
        shutdownGrace = b.shutdownGrace;
        idleTimeout = b.idleTimeout;
        headTimeout = b.headTimeout;
        responseTimeout = b.responseTimeout;
        lingerTimeout = b.lingerTimeout;
        lingerQuietTimeout = b.lingerQuietTimeout;
        shutdownLingerTimeout = b.shutdownLingerTimeout;
        handshakeTimeout = b.handshakeTimeout;
        tls = b.tls;
        maxDiscardedInput = b.maxDiscardedInput;
        maxConnections = b.maxConnections;
        maxLingeringConnections = b.maxLingeringConnections;
        maxPipelinedRequests = b.maxPipelinedRequests;
        maxInFlightBodyBytes = b.maxInFlightBodyBytes;
        maxRequestLine = b.maxRequestLine;
        maxHeaderBytes = b.maxHeaderBytes;
        ioThreads = b.ioThreads;
    }

    /**
     * Returns the default options.
     * @return shared immutable defaults, each setting at the default given on its builder method
     */
    public static ListenerOptions defaults() { return DEFAULTS; }

    /**
     * Starts a builder with every setting at its default.
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Starts a builder holding these options' values.
     * @return a new builder
     */
    public Builder toBuilder() {
        var b = new Builder();
        b.name = name;
        b.shutdownGrace = shutdownGrace;
        b.idleTimeout = idleTimeout;
        b.headTimeout = headTimeout;
        b.responseTimeout = responseTimeout;
        b.lingerTimeout = lingerTimeout;
        b.lingerQuietTimeout = lingerQuietTimeout;
        b.shutdownLingerTimeout = shutdownLingerTimeout;
        b.handshakeTimeout = handshakeTimeout;
        b.tls = tls;
        b.maxDiscardedInput = maxDiscardedInput;
        b.maxConnections = maxConnections;
        b.maxLingeringConnections = maxLingeringConnections;
        b.maxPipelinedRequests = maxPipelinedRequests;
        b.maxInFlightBodyBytes = maxInFlightBodyBytes;
        b.maxRequestLine = maxRequestLine;
        b.maxHeaderBytes = maxHeaderBytes;
        b.ioThreads = ioThreads;
        b.rejectionObserver = rejectionObserver;
        return b;
    }

    /** @return how long closing the listener lets in-flight exchanges finish before interrupting them */
    public Duration shutdownGrace() { return shutdownGrace; }
    /** @return network inactivity after which a connection closes */
    public Duration idleTimeout() { return idleTimeout; }
    /** @return bound from a request's first byte until its head is complete */
    public Duration headTimeout() { return headTimeout; }
    /** @return bound on writing one response to the socket */
    public Duration responseTimeout() { return responseTimeout; }
    /** @return total bound on reading and discarding input after the last response */
    public Duration lingerTimeout() { return lingerTimeout; }
    /** @return silence after which lingering ends */
    public Duration lingerQuietTimeout() { return lingerQuietTimeout; }
    /** @return total linger bound once the listener is closing */
    public Duration shutdownLingerTimeout() { return shutdownLingerTimeout; }
    /** @return bound on the TLS handshake of a new connection; applies only when {@link #tls()} is present */
    public Duration handshakeTimeout() { return handshakeTimeout; }
    /** @return the TLS settings; empty when the listener serves plain HTTP */
    public Optional<TlsOptions> tls() { return Optional.ofNullable(tls); }
    /** @return bytes discarded unread after an error or last response before the connection is closed */
    public int maxDiscardedInput() { return maxDiscardedInput; }
    /** @return open connections per listener */
    public int maxConnections() { return maxConnections; }
    /** @return connections that may linger without holding a regular connection slot */
    public int maxLingeringConnections() { return maxLingeringConnections; }
    /** @return outstanding requests per connection, including the running one */
    public int maxPipelinedRequests() { return maxPipelinedRequests; }
    /** @return request body bytes this listener holds at once, across all its connections */
    public long maxInFlightBodyBytes() { return maxInFlightBodyBytes; }
    /** @return longest request line in bytes */
    public int maxRequestLine() { return maxRequestLine; }
    /** @return largest header section in bytes */
    public int maxHeaderBytes() { return maxHeaderBytes; }
    /** @return number of I/O threads per listener */
    public int ioThreads() { return ioThreads; }

    /** @return the observer of the listener's own error responses, if one was set */
    public Optional<RejectionObserver> rejectionObserver() { return Optional.ofNullable(rejectionObserver); }

    /** @return the listener's name, the value of the {@code listener} metric tag */
    public String name() { return name; }

    @Override
    public String toString() {
        return "ListenerOptions[name=" + name + ", shutdownGrace=" + shutdownGrace + ", idleTimeout=" + idleTimeout
                + ", headTimeout=" + headTimeout + ", responseTimeout=" + responseTimeout
                + ", lingerTimeout=" + lingerTimeout + ", lingerQuietTimeout=" + lingerQuietTimeout
                + ", shutdownLingerTimeout=" + shutdownLingerTimeout + ", handshakeTimeout=" + handshakeTimeout
                + ", tls=" + tls + ", maxDiscardedInput=" + maxDiscardedInput
                + ", maxConnections=" + maxConnections + ", maxLingeringConnections=" + maxLingeringConnections
                + ", maxPipelinedRequests=" + maxPipelinedRequests + ", maxInFlightBodyBytes=" + maxInFlightBodyBytes
                + ", maxRequestLine=" + maxRequestLine
                + ", maxHeaderBytes=" + maxHeaderBytes + ", ioThreads=" + ioThreads + "]";
    }

    /**
     * Collects settings before {@link #build()}. Each setter validates its value at once and throws
     * {@link IllegalArgumentException} with the setting's name and valid range. Not thread-safe;
     * the built options are immutable.
     */
    public static final class Builder {
        private String name = "default";
        private Duration shutdownGrace = Duration.ofSeconds(5);
        private Duration idleTimeout = Duration.ofSeconds(30);
        private Duration headTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(30);
        private Duration lingerTimeout = Duration.ofSeconds(2);
        private Duration lingerQuietTimeout = Duration.ofMillis(500);
        private Duration shutdownLingerTimeout = Duration.ofMillis(500);
        private Duration handshakeTimeout = Duration.ofSeconds(10);
        private TlsOptions tls;
        private int maxDiscardedInput = 16 * 1024 * 1024;
        private int maxConnections = 128;
        private int maxLingeringConnections = 32;
        private int maxPipelinedRequests = 8;
        private long maxInFlightBodyBytes = 64L * 1024 * 1024;
        private int maxRequestLine = 4096;
        private int maxHeaderBytes = 8192;
        private int ioThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
        private RejectionObserver rejectionObserver;

        private Builder() { }

        /**
         * Names the listener. The name is the value of the {@code listener} tag of the
         * listener-level metrics (connections, bytes and answers given before admission), so
         * listeners that share an application can be told apart. It is chosen by the operator, never
         * by a client, and should come from a small fixed set.
         * @param name lowercase letters, digits and underscores starting with a letter, at most
         *        {@value ListenerOptions#MAX_NAME_LENGTH} characters; the default is {@code default}
         * @return this builder
         * @throws IllegalArgumentException if invalid
         */
        public Builder name(String name) {
            Objects.requireNonNull(name, "name");
            if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("name must be lowercase letters, digits and underscores starting with a letter, at most "
                        + MAX_NAME_LENGTH + " characters: " + name);
            }
            this.name = name;
            return this;
        }

        /**
         * Sets how long closing the listener lets running exchanges finish. After it, remaining
         * connections close and their handlers are interrupted. Zero interrupts at once.
         * @param grace from zero to one day; the default is 5 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder shutdownGrace(Duration grace) {
            Objects.requireNonNull(grace, "shutdownGrace");
            if (grace.isNegative() || grace.compareTo(MAX_DURATION) > 0) {
                throw new IllegalArgumentException("shutdownGrace must be from 0 to " + MAX_DURATION + ": " + grace);
            }
            shutdownGrace = grace;
            return this;
        }

        /**
         * Sets the network inactivity (no read and no write progress) after which a connection
         * closes. It does not interrupt a waiting or running request.
         * @param timeout 1 millisecond to one day; the default is 30 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder idleTimeout(Duration timeout) {
            idleTimeout = duration("idleTimeout", timeout);
            return this;
        }

        /**
         * Sets how long a request head may take from its first byte; a slower client receives 408.
         * @param timeout 1 millisecond to one day; the default is 10 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder headTimeout(Duration timeout) {
            headTimeout = duration("headTimeout", timeout);
            return this;
        }

        /**
         * Sets how long writing one response may take, from handing it to the socket until the
         * operating system has accepted its last byte; a slower reader loses the connection.
         * @param timeout 1 millisecond to one day; the default is 30 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder responseTimeout(Duration timeout) {
            responseTimeout = duration("responseTimeout", timeout);
            return this;
        }

        /**
         * Sets how long a connection may linger after its last response, reading and discarding
         * input so a client that is still sending reads the whole response.
         * @param timeout total bound, 1 millisecond to one day; the default is 2 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder lingerTimeout(Duration timeout) {
            lingerTimeout = duration("lingerTimeout", timeout);
            return this;
        }

        /**
         * Sets the silence after which lingering ends because the client is taken to have
         * finished. Each arriving byte restarts it; it never extends {@link #lingerTimeout}.
         * @param timeout 1 millisecond to one day; the default is 500 milliseconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder lingerQuietTimeout(Duration timeout) {
            lingerQuietTimeout = duration("lingerQuietTimeout", timeout);
            return this;
        }

        /**
         * Sets the total linger bound once the listener is closing (the smaller of this and
         * {@link #lingerTimeout} applies).
         * @param timeout 1 millisecond to one day; the default is 500 milliseconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder shutdownLingerTimeout(Duration timeout) {
            shutdownLingerTimeout = duration("shutdownLingerTimeout", timeout);
            return this;
        }

        /**
         * Sets how long a new connection may take to finish its TLS handshake. A client that is
         * slower, or that sends something other than TLS, loses the connection; until then it
         * holds a connection slot (see {@link #maxConnections(int)}) but no admission capacity.
         * Ignored without {@link #tls(TlsOptions)}.
         * @param timeout 1 millisecond to one day; the default is 10 seconds
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder handshakeTimeout(Duration timeout) {
            handshakeTimeout = duration("handshakeTimeout", timeout);
            return this;
        }

        /**
         * Serves HTTPS instead of plain HTTP on this listener. The material is validated when the
         * listener starts. Null serves plain HTTP, which is the default.
         * @param options the TLS settings, or null
         * @return this builder
         */
        public Builder tls(TlsOptions options) {
            tls = options;
            return this;
        }

        /**
         * Sets how many bytes a connection discards unread after an error or its last response
         * before it is closed as abusive.
         * @param bytes from zero to 1 GiB; the default is 16 MiB
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxDiscardedInput(int bytes) {
            maxDiscardedInput = range("maxDiscardedInput", bytes, 0, 1 << 30);
            return this;
        }

        /**
         * Sets the open connections per listener. A connection over the cap is closed at once
         * without a response and without consuming a slot. Connections that only linger count
         * against {@link #maxLingeringConnections} instead while that pool has room.
         *
         * <p>Relation to admission: a connection carries at most {@link #maxPipelinedRequests}
         * outstanding requests, but a client usually keeps one in flight, so the requests a listener
         * can hold at once are roughly bounded by this cap. A cap below the application's admission
         * capacity (active plus queued requests) leaves part of that capacity unreachable: clients
         * are refused at the connection level before admission could queue or reject them with 503.
         * Set the cap at or above that capacity, plus headroom for idle keep-alive connections.
         * @param connections 1 to 1,000,000; the default is 128
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxConnections(int connections) {
            maxConnections = range("maxConnections", connections, 1, 1_000_000);
            return this;
        }

        /**
         * Sets how many lingering connections move to a separate pool and stop counting against
         * {@link #maxConnections}. Beyond it a lingering connection keeps its regular slot, so a
         * listener has at most {@code maxConnections + maxLingeringConnections} open sockets. Zero
         * keeps lingering connections on their regular slots.
         * @param connections 0 to 1,000,000; the default is 32
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxLingeringConnections(int connections) {
            maxLingeringConnections = range("maxLingeringConnections", connections, 0, 1_000_000);
            return this;
        }

        /**
         * Sets the outstanding requests per connection, including the running one. A further
         * request is answered 503 after the earlier responses and the connection closes.
         * @param requests 1 to 1024; the default is 8
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxPipelinedRequests(int requests) {
            maxPipelinedRequests = range("maxPipelinedRequests", requests, 1, 1024);
            return this;
        }

        /**
         * Sets the request body bytes the listener holds at once, summed over all its connections:
         * bodies being received, bodies of pipelined requests waiting behind a running one, and
         * the bodies of requests whose handler is running. A declared {@code Content-Length} is
         * reserved in full when the request head arrives and a chunked body is counted as it
         * arrives; the reservation is returned when the request's response is final or its
         * connection ends. A request that does not fit is answered 503 and its connection closes
         * after the earlier responses; the handler is never invoked. The default equals the largest
         * configurable {@code maxRequestBody}, so any permitted body fits when the listener is
         * otherwise idle. A listener refuses to start when the budget is smaller than the
         * application's {@code maxRequestBody}, because no such body could ever be accepted.
         * @param bytes 1 to 1 TiB; the default is 64 MiB
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxInFlightBodyBytes(long bytes) {
            if (bytes < 1 || bytes > (1L << 40)) {
                throw new IllegalArgumentException("maxInFlightBodyBytes must be from 1 to " + (1L << 40) + ": " + bytes);
            }
            maxInFlightBodyBytes = bytes;
            return this;
        }

        /**
         * Sets the longest request line; a longer one is answered 414.
         * @param bytes 256 to 65,536; the default is 4096
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxRequestLine(int bytes) {
            maxRequestLine = range("maxRequestLine", bytes, 256, 65_536);
            return this;
        }

        /**
         * Sets the largest header section; a larger one is answered 431.
         * @param bytes 256 to 1 MiB; the default is 8192
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder maxHeaderBytes(int bytes) {
            maxHeaderBytes = range("maxHeaderBytes", bytes, 256, 1024 * 1024);
            return this;
        }

        /**
         * Sets the I/O threads of the listener. User handlers never run on them.
         * @param threads 1 to 1024; the default is the number of available processors, at least 2
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder ioThreads(int threads) {
            ioThreads = range("ioThreads", threads, 1, 1024);
            return this;
        }

        /**
         * Sets an observer called for each error response the listener generates itself, such as
         * 413, 431 or 503, for metrics and logging. It is read-only; see {@link RejectionObserver}.
         * @param observer the observer; one is replaced by the next call
         * @return this builder
         */
        public Builder rejectionObserver(RejectionObserver observer) {
            rejectionObserver = Objects.requireNonNull(observer, "rejectionObserver");
            return this;
        }

        /**
         * Builds immutable options.
         * @return the options
         */
        public ListenerOptions build() { return new ListenerOptions(this); }

        private static Duration duration(String name, Duration value) {
            Objects.requireNonNull(value, name);
            if (value.compareTo(MIN_DURATION) < 0 || value.compareTo(MAX_DURATION) > 0) {
                throw new IllegalArgumentException(name + " must be from " + MIN_DURATION + " to " + MAX_DURATION
                        + ": " + value);
            }
            return value;
        }

        private static int range(String name, int value, int min, int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be from " + min + " to " + max + ": " + value);
            }
            return value;
        }
    }
}
