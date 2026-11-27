package com.jsgalactic.axiom.graphql;

import com.jsgalactic.axiom.execution.ExecutionContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.language.Field;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.validation.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composes graphql-java instrumentation that bounds work and carries the Axiom deadline. */
final class Limits {
    static final String EXECUTION_KEY = "com.jsgalactic.axiom.execution";

    private Limits() { }

    static Instrumentation instrumentation(GraphQL runtime, GraphQlOptions options) {
        var parts = new ArrayList<Instrumentation>();
        if (runtime != null && runtime.getInstrumentation() != null) {
            parts.add(runtime.getInstrumentation());
        }
        parts.add(new MaxDepth(options.maxDepth()));
        parts.add(new MaxComplexity(options.maxComplexity()));
        if (!options.introspection()) {
            parts.add(new NoIntrospection());
        }
        parts.add(new Deadline());
        return new ChainedInstrumentation(List.copyOf(parts));
    }

    private static final class MaxDepth extends MaxQueryDepthInstrumentation {
        MaxDepth(int maxDepth) { super(maxDepth); }

        @Override
        protected AbortExecutionException mkAbortException(int maxDepth, int actualDepth) {
            return new AbortExecutionException(List.of(new SafeError("graphql_query_too_deep")));
        }
    }

    private static final class MaxComplexity extends MaxQueryComplexityInstrumentation {
        MaxComplexity(int maxComplexity) { super(maxComplexity); }

        @Override
        protected AbortExecutionException mkAbortException(int maxComplexity, int actualComplexity) {
            return new AbortExecutionException(List.of(new SafeError("graphql_query_too_complex")));
        }
    }

    private static final class NoIntrospection extends SimplePerformantInstrumentation {
        @Override
        public InstrumentationContext<List<ValidationError>> beginValidation(
                InstrumentationValidationParameters parameters, InstrumentationState state) {
            var found = new AtomicBoolean();
            // The visitor's Node type is raw in graphql-java's own API; the nodes are only read here.
            @SuppressWarnings("rawtypes")
            NodeVisitorStub visitor = new NodeVisitorStub() {
                @Override
                public TraversalControl visitField(Field field, TraverserContext<Node> context) {
                    if (field.getName().startsWith("__")) {
                        found.set(true);
                    }
                    return TraversalControl.CONTINUE;
                }
            };
            new NodeTraverser().depthFirst(visitor, parameters.getDocument());
            if (found.get()) {
                throw new AbortExecutionException(List.of(new SafeError("graphql_introspection_disabled")));
            }
            return SimpleInstrumentationContext.noOp();
        }
    }

    private static final class Deadline extends SimplePerformantInstrumentation {
        @Override
        public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
            check(parameters.getExecutionContext().getExecutionInput());
            return SimpleInstrumentationContext.noOp();
        }

        @Override
        public ExecutionStrategyInstrumentationContext beginExecutionStrategy(
                InstrumentationExecutionStrategyParameters parameters, InstrumentationState state) {
            check(parameters.getExecutionContext().getExecutionInput());
            return ExecutionStrategyInstrumentationContext.NOOP;
        }

        private static void check(ExecutionInput input) {
            if (input == null) {
                return;
            }
            Object value = input.getGraphQLContext().get(EXECUTION_KEY);
            if (value instanceof ExecutionContext execution && execution.isExpired()) {
                input.cancel();
                throw new AbortExecutionException(List.of(new SafeError("graphql_deadline_expired")));
            }
        }
    }
}
