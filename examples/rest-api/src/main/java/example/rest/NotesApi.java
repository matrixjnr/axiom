package example.rest;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.BodyValidator;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.Violation;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A small JSON API: create notes for an owner and read them back. */
public final class NotesApi {
    /** Request body for creating a note. */
    public record NewNote(String title, String text) { }

    /** A stored note, returned as JSON. */
    public record Note(long id, String owner, String title, String text) { }

    /** Thrown by the store for an unknown note; mapped to 404 by an error handler. */
    static final class NoteNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoteNotFoundException() { super(null, null, false, false); }
    }

    private static final System.Logger LOG = System.getLogger(NotesApi.class.getName());

    /** Field rules for new notes; shared by every request, so it keeps no state. */
    static final BodyValidator<NewNote> NEW_NOTE = note -> {
        var violations = new ArrayList<Violation>();
        if (note.title() == null || note.title().isBlank()) { violations.add(new Violation("title", "required")); }
        else if (note.title().length() > 100) { violations.add(new Violation("title", "too_long")); }
        if (note.text() != null && note.text().length() > 4000) { violations.add(new Violation("text", "too_long")); }
        return violations;
    };

    /**
     * Logs method, route template, status and duration of each request, and reports the duration
     * in a {@code Server-Timing} header. Stateless, so one instance serves all requests.
     */
    static final Middleware TIMING = (ctx, next) -> {
        long start = System.nanoTime();
        var response = next.run();
        long micros = (System.nanoTime() - start) / 1_000;
        LOG.log(System.Logger.Level.INFO, () -> ctx.execution().requestId() + " " + ctx.method() + " "
                + ctx.route().path() + " " + response.status() + " " + micros + "us");
        return response.withHeader("Server-Timing", "app;dur=" + micros / 1000.0);
    };

    private NotesApi() {}

    /**
     * Registers the routes on a new application.
     * @return configured application, not yet started
     */
    public static Application create() {
        var notes = new ConcurrentHashMap<Long, Note>();
        var ids = new AtomicLong();
        var app = Axiom.create();
        app.maxRequestBody(16 * 1024);
        app.error(NoteNotFoundException.class, (ctx, missing) -> { throw new NotFoundException("note_not_found"); });
        app.group("/owners/:owner/notes", owner -> {
            owner.use(TIMING);
            owner.post("", ctx -> {
                var request = ctx.validatedBody(NewNote.class, NEW_NOTE);
                var note = new Note(ids.incrementAndGet(), ctx.pathDecoded("owner"), request.title(), request.text());
                notes.put(note.id(), note);
                return ctx.status(201).json(note).withLocation(ctx.path() + "/" + note.id());
            });
            owner.get("/:id", ctx -> ctx.json(find(notes, ctx)));
        });
        return app;
    }

    private static Note find(ConcurrentHashMap<Long, Note> notes, Context ctx) {
        Note note;
        try { note = notes.get(Long.parseLong(ctx.path("id"))); }
        catch (NumberFormatException notAnId) { note = null; }
        if (note == null || !note.owner().equals(ctx.pathDecoded("owner"))) { throw new NoteNotFoundException(); }
        return note;
    }

    /**
     * Serves the API on http://127.0.0.1:8080 until the process stops.
     * @param args unused
     * @throws Exception if the listener cannot start
     */
    public static void main(String[] args) throws Exception {
        var app = create();
        var server = app.listen(8080);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "notes-shutdown"));
        System.out.println("Listening on http://127.0.0.1:" + server.localAddress().getPort() + "/ ");
        server.termination().toCompletableFuture().join();
    }
}
