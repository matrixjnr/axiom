package io.axiom.http.internal;

import java.time.Duration;

/**
 * The listener's fixed timing bounds. Production always uses {@link #DEFAULTS}, built from the
 * documented constants; tests vary single values to stay independent of machine speed or to
 * observe one bound without waiting for another. Immutable and shared across connections.
 *
 * @param shutdownGrace how long close() lets in-flight exchanges finish before interrupting them
 * @param headTimeout bound from a request's first byte until its head is complete
 * @param idleTimeout network inactivity after which a connection closes
 * @param linger total bound on reading and discarding input after the last response
 */
record TransportSettings(Duration shutdownGrace, Duration headTimeout, Duration idleTimeout, Duration linger) {
    /** The production values. */
    static final TransportSettings DEFAULTS = new TransportSettings(NettyServer.SHUTDOWN_GRACE,
            HttpConnection.REQUEST_HEAD_TIMEOUT, NettyServer.IDLE_TIMEOUT, HttpConnection.LINGER_TIMEOUT);

    TransportSettings withShutdownGrace(Duration value) {
        return new TransportSettings(value, headTimeout, idleTimeout, linger);
    }

    TransportSettings withHeadTimeout(Duration value) {
        return new TransportSettings(shutdownGrace, value, idleTimeout, linger);
    }

    TransportSettings withIdleTimeout(Duration value) {
        return new TransportSettings(shutdownGrace, headTimeout, value, linger);
    }

    TransportSettings withLinger(Duration value) {
        return new TransportSettings(shutdownGrace, headTimeout, idleTimeout, value);
    }
}
