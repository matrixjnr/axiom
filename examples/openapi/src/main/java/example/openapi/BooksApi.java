package example.openapi;

import static com.jsgalactic.axiom.validation.Rule.maxLength;
import static com.jsgalactic.axiom.validation.Rule.notBlank;
import static com.jsgalactic.axiom.validation.Rule.range;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.openapi.OpenApi;
import com.jsgalactic.axiom.openapi.SecurityScheme;
import com.jsgalactic.axiom.routing.RouteDoc;
import com.jsgalactic.axiom.validation.Rules;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A small books API whose routes are described for OpenAPI and Swagger. */
public final class BooksApi {
    // region records
    /** Request body for adding a book. */
    public record NewBook(String title, String author, int year) { }

    /** A stored book. */
    public record Book(long id, String title, String author, int year) { }
    // endregion records

    // region bean
    /**
     * An ordinary JavaBean, shaped exactly like Lombok's {@code @Getter @Setter} output: private
     * fields with public getters and setters, and {@code is} for a primitive boolean.
     */
    public static class Reader {
        private String name;
        private boolean active;
        private List<String> favourites;

        /** Creates an empty reader, as the JSON codec requires. */
        public Reader() { }

        /** @return the reader's name */
        public String getName() { return name; }

        /** @param name the reader's name */
        public void setName(String name) { this.name = name; }

        /** @return true while the reader is active */
        public boolean isActive() { return active; }

        /** @param active true while the reader is active */
        public void setActive(boolean active) { this.active = active; }

        /** @return titles the reader likes */
        public List<String> getFavourites() { return favourites; }

        /** @param favourites titles the reader likes */
        public void setFavourites(List<String> favourites) { this.favourites = favourites; }
    }
    // endregion bean

    /** Rules for new books; the OpenAPI document mirrors them as schema constraints. */
    static final Rules<NewBook> NEW_BOOK = Rules.of(NewBook.class)
            .field("title", NewBook::title, notBlank(), maxLength(120))
            .field("author", NewBook::author, notBlank())
            .field("year", NewBook::year, range(1400, 2100));

    private BooksApi() { }

    /**
     * Registers the routes on a new application.
     *
     * @param docsAccess middleware that guards the documentation routes, or null to not serve
     *        any documentation (for example in production)
     * @return the configured application, not yet started
     */
    public static Application create(Middleware docsAccess) {
        var books = new ConcurrentHashMap<Long, Book>();
        var ids = new AtomicLong();
        var app = Axiom.create();

        // region describe-route
        var add = app.post("/books", ctx -> {
            var request = ctx.validatedBody(NewBook.class, NEW_BOOK);
            var book = new Book(ids.incrementAndGet(), request.title(), request.author(), request.year());
            books.put(book.id(), book);
            return ctx.status(201).json(book);
        });
        app.describe(add, RouteDoc.summary("Add a book")
                .description("Stores a new book and returns it with its id.")
                .tags("books").operationId("addBook")
                .requestBody(NewBook.class)
                .response(201, "The stored book", Book.class)
                .response(422, "The book is not valid")
                .security("bearer"));
        // endregion describe-route

        // region describe-params
        var read = app.get("/books/:id", ctx -> {
            var book = books.get(ctx.pathLong("id"));
            if (book == null) { throw new NotFoundException("book_not_found"); }
            return ctx.json(book);
        });
        app.describe(read, RouteDoc.summary("Read a book").tags("books").operationId("readBook")
                .pathParam("id", long.class, "The book id")
                .headerParam("X-Request-Id", UUID.class, "Correlation id, echoed in logs", false)
                .response(200, "The book", Book.class)
                .response(404, "No such book"));

        var list = app.get("/books", ctx -> ctx.json(List.copyOf(books.values())));
        app.describe(list, RouteDoc.summary("List books").tags("books").operationId("listBooks")
                .queryParam("author", String.class, "Only books by this author", false)
                .response(200, "All books", RouteDoc.listOf(Book.class)));
        // endregion describe-params

        // region describe-bean
        var reader = app.put("/readers/:name", ctx -> ctx.json(ctx.body(Reader.class)));
        app.describe(reader, RouteDoc.summary("Save a reader").tags("readers").operationId("saveReader")
                .requestBody(Reader.class)
                .response(200, "The saved reader", Reader.class));
        // endregion describe-bean

        // region openapi
        var openApi = OpenApi.builder("Books API", "1.0.0")
                .description("Add and read books.")
                .securityScheme("bearer", SecurityScheme.bearer("JWT"))
                .rules(NewBook.class, NEW_BOOK)
                .defaults("/readers", RouteDoc.empty().tags("people"))
                .defaults("/books", RouteDoc.empty().response(500, "Unexpected server error"))
                .build();
        // endregion openapi

        // region serve
        if (docsAccess != null) {
            openApi.serve(app, "/openapi.json", docsAccess);        // OpenAPI 3.1
            openApi.serveSwagger(app, "/swagger.json", docsAccess); // Swagger 2.0
        }
        // endregion serve
        return app;
    }

    /**
     * Serves the API on http://127.0.0.1:8080. The documentation routes exist only when the
     * environment variable {@code DOCS_KEY} is set, and then require it in {@code X-Docs-Key}.
     *
     * @param args unused
     * @throws Exception if the listener cannot start
     */
    public static void main(String[] args) throws Exception {
        var key = System.getenv("DOCS_KEY");
        var app = create(key == null || key.isBlank() ? null : DocsAccess.requireKey(key)).closeOnJvmShutdown();
        var server = app.listen(8080);
        System.out.println("Listening on http://127.0.0.1:" + server.localAddress().getPort() + "/");
        server.termination().toCompletableFuture().join();
    }
}
