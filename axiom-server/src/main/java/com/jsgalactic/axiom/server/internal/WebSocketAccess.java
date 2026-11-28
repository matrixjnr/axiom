package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import java.util.Optional;

/**
 * Internal bridge from an application to the WebSocket routes it registered. Transports and the
 * test client use it to find a handler for a handshake without adding a lookup method to the public
 * application API. Not application API; available only on applications created by this runtime.
 */
public interface WebSocketAccess {
  /**
   * Finds the route registered for the path of a GET handshake request.
   *
   * @param request handshake request; its method is GET
   * @return the matching route and handler, or empty when no WebSocket route matches
   */
  Optional<WebSocketRoute> route(Request request);

  /**
   * Obtains the bridge of an application created by this runtime.
   *
   * @param application configured, running or closed application
   * @return its WebSocket bridge
   * @throws IllegalArgumentException if the application was not created by this runtime
   */
  static WebSocketAccess of(Application application) {
    if (application instanceof WebSocketAccess access) {
      return access;
    }
    throw new IllegalArgumentException(
        "Application does not own Axiom WebSocket routes: " + application);
  }

  /**
   * A registered route and the handler it owns; the route's method is always GET. The admission key
   * is private to the registration so it cannot collide with an HTTP route of the same path.
   */
  record WebSocketRoute(Route route, WebSocketHandler handler, Object admissionKey) {}
}
