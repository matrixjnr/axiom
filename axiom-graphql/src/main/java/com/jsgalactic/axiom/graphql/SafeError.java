package com.jsgalactic.axiom.graphql;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import java.io.Serial;
import java.util.List;
import java.util.Map;

/** A GraphQL error whose client-visible code is chosen by this adapter, never by an exception. */
final class SafeError implements GraphQLError {
  @Serial private static final long serialVersionUID = 1L;
  private final String code;

  SafeError(String code) {
    this.code = code;
  }

  String code() {
    return code;
  }

  @Override
  public String getMessage() {
    return "GraphQL request rejected";
  }

  @Override
  public List<SourceLocation> getLocations() {
    return List.of();
  }

  @Override
  public ErrorClassification getErrorType() {
    return ErrorType.ExecutionAborted;
  }

  @Override
  public Map<String, Object> getExtensions() {
    return Map.of("code", code);
  }

  @Override
  public String toString() {
    return code;
  }
}
