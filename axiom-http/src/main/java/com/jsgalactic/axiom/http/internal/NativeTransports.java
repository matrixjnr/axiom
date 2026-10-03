package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.TransportKind;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;

/**
 * Detects the optional native transports. The Netty classes for epoll and kqueue are compile-time
 * dependencies only: they are on the class path when an application adds the native artifact, and
 * absent otherwise. Every reference to them stays inside the two nested holder classes below, which
 * the JVM loads only when asked, so a missing library surfaces as a {@link LinkageError} caught
 * here and never reaches code that does not use it.
 */
final class NativeTransports {
    private NativeTransports() { }

    /**
     * The outcome of probing one native transport.
     * @param transport the usable transport, or null
     * @param reason why it is not usable, when it is not
     * @param cause the underlying failure, when there is one
     */
    record Availability(Transport transport, String reason, Throwable cause) {
        static Availability usable(Transport transport) { return new Availability(transport, null, null); }
        static Availability unusable(String reason, Throwable cause) { return new Availability(null, reason, cause); }
    }

    static Availability epoll() {
        try {
            return Epolls.probe();
        } catch (LinkageError missing) {
            return Availability.unusable("netty-transport-native-epoll is not on the class path", missing);
        }
    }

    static Availability kqueue() {
        try {
            return KQueues.probe();
        } catch (LinkageError missing) {
            return Availability.unusable("netty-transport-native-kqueue is not on the class path", missing);
        }
    }

    private static final class Epolls {
        static Availability probe() {
            if (!Epoll.isAvailable()) {
                return Availability.unusable("epoll is not available on this system", Epoll.unavailabilityCause());
            }
            return Availability.usable(new Transport(TransportKind.EPOLL, EpollIoHandler::newFactory, EpollServerSocketChannel.class));
        }
    }

    private static final class KQueues {
        static Availability probe() {
            if (!KQueue.isAvailable()) {
                return Availability.unusable("kqueue is not available on this system", KQueue.unavailabilityCause());
            }
            return Availability.usable(new Transport(TransportKind.KQUEUE, KQueueIoHandler::newFactory, KQueueServerSocketChannel.class));
        }
    }
}
