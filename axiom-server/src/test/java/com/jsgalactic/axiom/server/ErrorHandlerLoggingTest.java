package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
    private final Logger logger = Logger.getLogger("com.jsgalactic.axiom.failures");
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

    /** Captures what the application logs through a logger of its own. */
    private static final class Capture implements System.Logger {
        final List<String> entries = new CopyOnWriteArrayList<>();
        final List<Throwable> thrown = new CopyOnWriteArrayList<>();
        @Override public String getName() { return "capture"; }
        @Override public boolean isLoggable(Level level) { return true; }
        @Override public void log(Level level, java.util.ResourceBundle bundle, String message, Throwable thrown) {
            entries.add(level + " " + message);
            this.thrown.add(thrown);
        }
        @Override public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
            entries.add(level + " " + format);
        }
    }

    @Test
    void aTranslatedServerErrorIsLoggedOnceNotTwice() throws Exception {
        var failure = new NoSuchElementException(POISON);
        try (var app = app(failure)) {
            app.error(NoSuchElementException.class, (ctx, mapped) -> { throw new InternalServerErrorException(); });
            app.start();
            var response = call(app, ExecutionContext.create(Duration.ofSeconds(10)));
            assertThat(response.status()).isEqualTo(500);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isSameAs(failure);
                assertThat(record.getMessage()).contains("500");
            });
        }
    }

    @Test
    void anAxiomServerErrorWithoutAHandlerIsLoggedOnce() throws Exception {
        var failure = new InternalServerErrorException();
        try (var app = app(failure)) {
            app.start();
            assertThat(call(app, ExecutionContext.create(Duration.ofSeconds(10))).status()).isEqualTo(500);
            assertThat(records).singleElement().satisfies(record -> assertThat(record.getThrown()).isSameAs(failure));
        }
    }

    @Test
    void theLoggerAndLevelAreConfigurable() throws Exception {
        var capture = new Capture();
        var failure = new NoSuchElementException(POISON);
        try (var app = app(failure)) {
            app.failureLog(capture, System.Logger.Level.INFO);
            app.error(NoSuchElementException.class, (ctx, mapped) -> Response.of(409, "mapped"));
            app.start();
            var execution = ExecutionContext.create(Duration.ofSeconds(10));
            assertThat(call(app, execution).status()).isEqualTo(409);
            assertThat(records).as("the default logger stays silent").isEmpty();
            assertThat(capture.entries).singleElement().asString()
                    .startsWith("INFO ").contains(execution.requestId()).doesNotContain("POISON");
            assertThat(capture.thrown).containsExactly(failure);
        }
    }

    @Test
    void offSilencesMappedFailuresButNotADefectiveErrorHandler() throws Exception {
        var capture = new Capture();
        try (var app = app(new NoSuchElementException(POISON))) {
            app.failureLog(capture, System.Logger.Level.OFF);
            app.get("/defect", ctx -> { throw new IllegalStateException(); });
            app.error(NoSuchElementException.class, (ctx, mapped) -> Response.of(409, "mapped"));
            app.error(IllegalStateException.class, (ctx, mapped) -> { throw new UnsupportedOperationException(); });
            app.start();
            assertThat(call(app, ExecutionContext.create(Duration.ofSeconds(10))).status()).isEqualTo(409);
            assertThat(capture.entries).isEmpty();
            assertThat(app.handle(Request.get("/defect")).status()).isEqualTo(500);
            assertThat(capture.entries).singleElement().asString().startsWith("ERROR ");
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        try (var app = Axiom.create()) {
            assertThatNullPointerException().isThrownBy(() -> app.failureLog(null, System.Logger.Level.INFO));
            assertThatNullPointerException().isThrownBy(() -> app.failureLog(new Capture(), null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> app.failureLog(new Capture(), System.Logger.Level.ALL));
            app.start();
            assertThatIllegalStateException()
                    .isThrownBy(() -> app.failureLog(new Capture(), System.Logger.Level.INFO));
        }
    }
}
