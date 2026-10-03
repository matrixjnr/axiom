package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.TransportKind;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.function.Supplier;

/**
 * The Netty I/O mechanism a listener runs on: how its event loops poll and which server channel
 * accepts. Resolved once per listener from {@link TransportKind}; the native kinds are only
 * touched when their optional Netty libraries are on the class path (see {@link #resolve}).
 *
 * @param kind the concrete mechanism, never {@link TransportKind#AUTO}
 * @param handlers creates the I/O handler factory of one event loop group
 * @param serverChannel the channel class that listens and accepts
 */
record Transport(TransportKind kind, Supplier<IoHandlerFactory> handlers, Class<? extends ServerChannel> serverChannel) {
    static final Transport NIO = new Transport(TransportKind.NIO, NioIoHandler::newFactory, NioServerSocketChannel.class);

    /**
     * Chooses the mechanism for a request. {@code AUTO} takes epoll, then kqueue, when usable, and
     * NIO otherwise. A native kind named explicitly must be usable.
     * @throws IllegalStateException if an explicitly requested native transport is not usable;
     *         the message names the library to add and the cause carries Netty's reason when it has one
     */
    static Transport resolve(TransportKind requested) {
        return switch (requested) {
            case NIO -> NIO;
            case EPOLL -> require("EPOLL", "netty-transport-native-epoll", NativeTransports.epoll());
            case KQUEUE -> require("KQUEUE", "netty-transport-native-kqueue", NativeTransports.kqueue());
            case AUTO -> {
                var epoll = NativeTransports.epoll();
                if (epoll.transport() != null) { yield epoll.transport(); }
                var kqueue = NativeTransports.kqueue();
                yield kqueue.transport() != null ? kqueue.transport() : NIO;
            }
        };
    }

    private static Transport require(String name, String artifact, NativeTransports.Availability availability) {
        if (availability.transport() != null) { return availability.transport(); }
        throw new IllegalStateException("The " + name + " transport is not usable (" + availability.reason() + "); add "
                + artifact + " with the classifier of this platform to the runtime class path, or use TransportKind.AUTO or NIO",
                availability.cause());
    }
}
