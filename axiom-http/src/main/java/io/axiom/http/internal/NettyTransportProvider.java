package io.axiom.http.internal;

import io.axiom.application.Application;
import io.axiom.http.spi.HttpTransportProvider;
import io.axiom.lifecycle.Server;
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
