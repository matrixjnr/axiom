package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.spi.HttpTransportProvider;
import com.jsgalactic.axiom.lifecycle.Server;
import java.io.IOException;
import java.net.InetSocketAddress;

/** ServiceLoader entry point; not part of the application API. */
public final class NettyTransportProvider implements HttpTransportProvider {
    /** Creates the service provider. */
    public NettyTransportProvider() { }

    @Override
    public Server bind(Application application, InetSocketAddress address) throws IOException {
        return NettyServer.bind(application, address);
    }
}
