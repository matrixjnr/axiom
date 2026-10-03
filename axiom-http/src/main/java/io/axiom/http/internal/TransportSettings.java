package io.axiom.http.internal;

import java.time.Duration;

/**
 * The listener's fixed timing bounds and socket options. Production always uses {@link #DEFAULTS},
 * built from the documented constants; tests vary single values to stay independent of machine
 * speed and kernel buffer sizes, or to observe one bound without waiting for another. Immutable
 * and shared across connections.
 *
 * @param shutdownGrace how long close() lets in-flight exchanges finish before interrupting them
 * @param headTimeout bound from a request's first byte until its head is complete
 * @param idleTimeout network inactivity after which a connection closes
 * @param linger total bound on reading and discarding input after the last response
 * @param responseTimeout bound on writing one response, from handing it to the socket until the
 *     last byte has been accepted by the operating system
 * @param receiveBuffer SO_RCVBUF for accepted connections in bytes; zero keeps the system default
 * @param sendBuffer SO_SNDBUF for accepted connections in bytes; zero keeps the system default
 */
record TransportSettings(Duration shutdownGrace, Duration headTimeout, Duration idleTimeout, Duration linger,
        Duration responseTimeout, int receiveBuffer, int sendBuffer) {
    /** The production values. */
    static final TransportSettings DEFAULTS = new TransportSettings(NettyServer.SHUTDOWN_GRACE,
            HttpConnection.REQUEST_HEAD_TIMEOUT, NettyServer.IDLE_TIMEOUT, HttpConnection.LINGER_TIMEOUT,
            HttpConnection.RESPONSE_TIMEOUT, 0, 0);

    TransportSettings withShutdownGrace(Duration value) {
        return new TransportSettings(value, headTimeout, idleTimeout, linger, responseTimeout, receiveBuffer, sendBuffer);
    }

    TransportSettings withHeadTimeout(Duration value) {
        return new TransportSettings(shutdownGrace, value, idleTimeout, linger, responseTimeout, receiveBuffer, sendBuffer);
    }

    TransportSettings withIdleTimeout(Duration value) {
        return new TransportSettings(shutdownGrace, headTimeout, value, linger, responseTimeout, receiveBuffer, sendBuffer);
    }

    TransportSettings withLinger(Duration value) {
        return new TransportSettings(shutdownGrace, headTimeout, idleTimeout, value, responseTimeout, receiveBuffer, sendBuffer);
    }

    TransportSettings withResponseTimeout(Duration value) {
        return new TransportSettings(shutdownGrace, headTimeout, idleTimeout, linger, value, receiveBuffer, sendBuffer);
    }

    /** Fixes both socket buffers of accepted connections, so tests do not depend on kernel defaults. */
    TransportSettings withSocketBuffers(int receive, int send) {
        return new TransportSettings(shutdownGrace, headTimeout, idleTimeout, linger, responseTimeout, receive, send);
    }
}
