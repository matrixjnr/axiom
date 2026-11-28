package com.jsgalactic.axiom.jta;

import java.io.Serial;
import java.util.Locale;

/** Internal wrapper for a checked failure from the application's transaction manager. */
final class ManagerFailure extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final TransactionFailure kind;

  ManagerFailure(TransactionFailure kind, Throwable cause) {
    super("Transaction " + kind.name().toLowerCase(Locale.ROOT) + " failed", cause);
    this.kind = kind;
  }

  TransactionFailure kind() {
    return kind;
  }
}
