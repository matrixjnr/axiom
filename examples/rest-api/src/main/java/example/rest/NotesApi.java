package example.rest;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.error.NotFoundException;
import io.axiom.error.ValidationException;
import io.axiom.error.Violation;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A small JSON API: create notes for an owner and read them back. */
public final class NotesApi {
    /** Request body for creating a note. */
    public record NewNote(String title, String text) { }

    /** A stored note, returned as JSON. */
    public record Note(long id, String owner, String title, String text) { }

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
        app.post("/owners/:owner/notes", ctx -> {
            var request = ctx.body(NewNote.class);
            validate(request);
            var note = new Note(ids.incrementAndGet(), ctx.pathDecoded("owner"), request.title(), request.text());
            notes.put(note.id(), note);
            return ctx.status(201).json(note).withLocation("/notes/" + note.id());
        });
        app.get("/notes/:id", ctx -> {
            Note note;
            try { note = notes.get(Long.parseLong(ctx.path("id"))); }
            catch (NumberFormatException notAnId) { note = null; }
            if (note == null) { throw new NotFoundException("note_not_found"); }
            return ctx.json(note);
        });
        return app;
    }

    private static void validate(NewNote note) {
        var violations = new ArrayList<Violation>();
        if (note.title() == null || note.title().isBlank()) { violations.add(new Violation("title", "required")); }
        else if (note.title().length() > 100) { violations.add(new Violation("title", "too_long")); }
        if (note.text() != null && note.text().length() > 4000) { violations.add(new Violation("text", "too_long")); }
        if (!violations.isEmpty()) { throw new ValidationException(violations); }
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
