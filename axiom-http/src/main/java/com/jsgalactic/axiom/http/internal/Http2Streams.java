package com.jsgalactic.axiom.http.internal;

import io.netty.channel.Channel;

/**
 * Tracks the open streams of one HTTP/2 connection so that an idle connection and a closing
 * listener can be told apart from one with exchanges still running. Every method runs on the
 * connection's event loop, which is also the event loop of its stream channels.
 */
final class Http2Streams {
  private final Channel connection;
  private int open;
  private boolean closing;

  Http2Streams(Channel connection) {
    this.connection = connection;
  }

  /**
   * Registers a stream until its channel closes; the last one closes a connection told to close.
   */
  void opened(Channel stream) {
    open++;
    stream
        .closeFuture()
        .addListener(
            ignored -> {
              open--;
              if (closing && open == 0) {
                connection.close();
              }
            });
  }

  /** Returns whether this connection has a stream with an exchange in progress. */
  boolean busy() {
    return open > 0;
  }

  /** Sends GOAWAY now and closes the connection when its last stream ends. */
  void closeWhenDone() {
    closing = true;
    if (open == 0) {
      connection.close();
    }
  }
}
