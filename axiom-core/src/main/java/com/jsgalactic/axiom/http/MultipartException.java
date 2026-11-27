package com.jsgalactic.axiom.http;

import com.jsgalactic.axiom.error.BadRequestException;
import java.io.Serial;

/** A multipart body the parser cannot accept: malformed framing or an exceeded explicit limit. */
public final class MultipartException extends BadRequestException {
  @Serial private static final long serialVersionUID = 1L;

  /** Code of a body whose framing, headers or boundaries are not well formed. */
  public static final String MALFORMED = "malformed_multipart";

  /** Code of a body that exceeds a part, header, count or total-byte limit. */
  public static final String LIMIT_EXCEEDED = "multipart_limit_exceeded";

  /**
   * Creates a failure with a safe code and no input-derived detail.
   *
   * @param code {@link #MALFORMED} or {@link #LIMIT_EXCEEDED}
   */
  public MultipartException(String code) {
    super(code);
  }
}
