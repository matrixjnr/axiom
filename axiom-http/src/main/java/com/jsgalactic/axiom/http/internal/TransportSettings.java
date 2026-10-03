package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import java.time.Duration;

/**
 * A listener's bounds: the validated public {@link ListenerOptions} plus socket options that only
 * tests set. Production uses {@link #DEFAULTS} or the options the application passed to
 * {@code listen}; tests vary single values to stay independent of machine speed and kernel buffer
 * sizes, or to observe one bound without waiting for another. Immutable and shared across connections.
 *
 * @param options the public limits and timeouts
 * @param receiveBuffer SO_RCVBUF for accepted connections in bytes; zero keeps the system default
 * @param sendBuffer SO_SNDBUF for accepted connections in bytes; zero keeps the system default
 */
record TransportSettings(ListenerOptions options, int receiveBuffer, int sendBuffer) {
    /** The production values. */
    static final TransportSettings DEFAULTS = of(ListenerOptions.defaults());

    static TransportSettings of(ListenerOptions options) { return new TransportSettings(options, 0, 0); }

    /** How long close() lets in-flight exchanges finish before interrupting them. */
    Duration shutdownGrace() { return options.shutdownGrace(); }
    /** Bound from a request's first byte until its head is complete. */
    Duration headTimeout() { return options.headTimeout(); }
    /** Network inactivity after which a connection closes. */
    Duration idleTimeout() { return options.idleTimeout(); }
    /** Total bound on reading and discarding input after the last response. */
    Duration linger() { return options.lingerTimeout(); }
    /** Lingering ends once no input has arrived for this long. */
    Duration lingerQuiet() { return options.lingerQuietTimeout(); }
    /** Total linger bound once the listener is closing. */
    Duration shutdownLinger() { return options.shutdownLingerTimeout(); }
    /** Bound on writing one response. */
    Duration responseTimeout() { return options.responseTimeout(); }

    private TransportSettings with(ListenerOptions.Builder builder) {
        return new TransportSettings(builder.build(), receiveBuffer, sendBuffer);
    }

    TransportSettings withShutdownGrace(Duration value) { return with(options.toBuilder().shutdownGrace(value)); }
    TransportSettings withHeadTimeout(Duration value) { return with(options.toBuilder().headTimeout(value)); }
    TransportSettings withIdleTimeout(Duration value) { return with(options.toBuilder().idleTimeout(value)); }
    TransportSettings withLinger(Duration value) { return with(options.toBuilder().lingerTimeout(value)); }
    TransportSettings withLingerQuiet(Duration value) { return with(options.toBuilder().lingerQuietTimeout(value)); }
    TransportSettings withShutdownLinger(Duration value) {
        return with(options.toBuilder().shutdownLingerTimeout(value));
    }
    TransportSettings withResponseTimeout(Duration value) {
        return with(options.toBuilder().responseTimeout(value));
    }

    /** Applies other public options, keeping the test-only socket buffers. */
    TransportSettings withOptions(ListenerOptions value) { return new TransportSettings(value, receiveBuffer, sendBuffer); }

    /** Fixes both socket buffers of accepted connections, so tests do not depend on kernel defaults. */
    TransportSettings withSocketBuffers(int receive, int send) {
        return new TransportSettings(options, receive, send);
    }
}
