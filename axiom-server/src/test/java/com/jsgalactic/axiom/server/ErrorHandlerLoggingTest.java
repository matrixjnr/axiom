package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Failures mapped by application error handlers are logged server-side, never sent. */
class ErrorHandlerLoggingTest {
    private static final String POISON = "POISON<script>secret";
    // Held strongly so the logging configuration keeps the handler while the test runs.
    private final Logger logger = Logger.getLogger("com.jsgalactic.axiom.server.internal.DefaultApplication");
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach void capture() { logger.addHandler(capture); }

    @AfterEach void release() { logger.removeHandler(capture); }

    private static Application app(Exception failure) {
        var app = Axiom.create();
        app.get("/fail", ctx -> { throw failure; });
        return app;
    }

    private Response call(Application app, ExecutionContext execution) throws Exception {
        records.clear();
        return app.handle(Request.get("/fail"), execution);
    }

    @Test
    void logsUnexpectedFailuresAndServerErrorsWithTheRequestId() throws Exception {
        var failures = List.<Exception>of(new IllegalStateException(POISON), new InternalServerErrorException(),
                new BadRequestException());
        var statuses = List.of(409, 404, 503);
        for (int i = 0; i < failures.size(); i++) {
            var failure = failures.get(i);
            int status = statuses.get(i);
            try (var app = app(failure)) {
                app.error(Exception.class, (ctx, mapped) -> Response.of(status, "mapped"));
                app.error(com.jsgalactic.axiom.error.AxiomException.class, (ctx, mapped) -> Response.of(status, "mapped"));
                app.start();
                var execution = ExecutionContext.create(Duration.ofSeconds(10));
                var response = call(app, execution);
                assertThat(response.status()).isEqualTo(status);
                assertThat(String.valueOf(response.body())).doesNotContain("POISON");
                assertThat(records).as(failure.getClass().getSimpleName()).singleElement().satisfies(record -> {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    assertThat(record.getThrown()).isSameAs(failure);
                    assertThat(record.getMessage()).contains(execution.requestId()).contains(Integer.toString(status));
                });
            }
        }
    }

    @Test
    void logsTheOriginalFailureWhenAnErrorHandlerTranslatesIt() throws Exception {
        var failure = new NoSuchElementException(POISON);
        try (var app = app(failure)) {
            app.error(NoSuchElementException.class, (ctx, mapped) -> { throw new NotFoundException(); });
            app.start();
            var response = call(app, ExecutionContext.create(Duration.ofSeconds(10)));
            assertThat(response.status()).isEqualTo(404);
            assertThat(records).singleElement().satisfies(record -> assertThat(record.getThrown()).isSameAs(failure));
        }
    }

    @Test
    void doesNotLogExpectedClientErrors() throws Exception {
        try (var app = app(new NotFoundException("note_not_found"))) {
            app.error(NotFoundException.class, (ctx, mapped) -> Response.of(404, "custom"));
            app.start();
            assertThat(call(app, ExecutionContext.create(Duration.ofSeconds(10))).status()).isEqualTo(404);
            assertThat(records).isEmpty();
        }
    }
}
