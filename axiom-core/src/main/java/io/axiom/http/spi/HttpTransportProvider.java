package io.axiom.http.spi;

import io.axiom.application.Application;
import io.axiom.lifecycle.Server;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Service-provider boundary for HTTP transports. Implementations own all listener resources. */
public interface HttpTransportProvider {
    /**
     * Binds a running application. Failed binds must release all allocated resources.
     * @param application running application
     * @param address address to bind
     * @return owned listener
     * @throws IOException if binding fails
     */
    Server bind(Application application, InetSocketAddress address) throws IOException;
}
