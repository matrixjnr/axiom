package com.jsgalactic.axiom.lifecycle;

/**
 * The network I/O mechanism an HTTP listener uses, chosen with {@link
 * ListenerOptions.Builder#transport(TransportKind)}. Only the I/O mechanism differs: protocol
 * behavior, limits and timeouts are the same for every kind.
 *
 * <p>The native kinds need the matching optional native library on the class path, which the
 * installed transport lists in its documentation. A kind that is requested explicitly and is not
 * usable makes the listener fail to start with an {@link IllegalStateException}; {@link #AUTO}
 * never fails for that reason.
 */
public enum TransportKind {
  /**
   * Uses a native transport when one is usable on this machine ({@link #EPOLL} first, then {@link
   * #IO_URING} on Linux, then {@link #KQUEUE} on macOS and the BSDs) and {@link #NIO} otherwise.
   * This is the default.
   */
  AUTO,
  /** The JDK's selector-based I/O, available everywhere and needing no extra library. */
  NIO,
  /** Linux epoll through the transport's native library; requires Linux and that library. */
  EPOLL,
  /**
   * BSD and macOS kqueue through the transport's native library; requires such a system and that
   * library.
   */
  KQUEUE,
  /**
   * Linux io_uring through the transport's native library. Usable only where Netty's io_uring
   * classes and native library are on the class path, the kernel supports io_uring, and the process
   * is allowed to use it (container seccomp policies often block it). Applications opt in by adding
   * {@code netty-transport-native-io_uring} with the platform classifier; {@link #AUTO} selects
   * this kind only when it is available and usable and no earlier native kind was selected.
   */
  IO_URING
}
