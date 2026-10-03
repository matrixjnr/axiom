# OpenAPI and Swagger

`axiom-openapi` (package `com.jsgalactic.axiom.openapi`) generates an **OpenAPI 3.1** document, or
the equivalent **Swagger 2.0** document, from an application's routes and the optional
descriptions you attach to them. It is opt-in, uses only the JDK and `axiom-validation`, and is
independent of the server, the transport and the JSON codec. Routes are described with plain
code, `RouteDoc` values; there are no annotations and no classpath scanning. The runtime never
reads a description while handling a request.

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:<version>"))
    implementation("com.jsgalactic.axiom:axiom-openapi")
}
```

The examples below are real code: they live in `examples/openapi`, are compiled and tested with
the build, and a check fails when this page quotes something different from the source.

## Step 1: describe a route

`Application.describe(route, RouteDoc)` attaches a description to a registered route before
startup. A `RouteDoc` is immutable; each method returns a new one. Request and response types are
plain `Type` values: a class, or `RouteDoc.type(Page.class, Note.class)` / `RouteDoc.listOf(Note.class)`
for generic types.

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#describe-route -->
```java
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
```

| `RouteDoc` method | Becomes |
| --- | --- |
| `summary`, `description`, `tags`, `operationId`, `deprecated` | The same operation fields. An operation id must be unique across the application |
| `pathParam(name, type, description)` | A required path parameter; the name must be a capture of the route template |
| `queryParam`, `headerParam(name, type, description, required)` | A query or header parameter |
| `requestBody(type[, mediaTypes...])` | The request body; media types default to `application/json` |
| `response(status, description[, type[, mediaTypes...]])` | A response, sorted by status; with a type it has a body schema |
| `security(names...)` | Security requirements; each name must be defined with `securityScheme`. Several names are alternatives |
| `hidden()` | The route is left out of the document |

Routes without a description still appear, with their method and path only and an undocumented
default response. Path captures (`:id`, `*path`) become `{id}` and `{path}` path parameters of
type string unless you describe them. A route that cannot be expressed (an extension method such
as `PROPFIND`, which OpenAPI has no operation for) must be hidden or generation fails.

Parameters take simple types: primitives and wrappers, `String`, enums, `UUID`, numbers, date and
time types, or a `List`/`Set` of those.

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#describe-params -->
```java
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
```

## Step 2: describe bodies, records and Lombok-style classes

A body or response type can be a record, an ordinary JavaBean class (including a class written by
Lombok's `@Data`, `@Value`, `@Getter`/`@Setter`: Lombok produces plain getters, setters and
constructors at compile time, which is all the generator reads), an enum, a primitive or wrapper,
`String`, `UUID`, `BigDecimal`, `java.time` values (ISO-8601 strings), a collection, an array, an
`Optional` or a `Map` with `String` keys.

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#records -->
```java
/** Request body for adding a book. */
public record NewBook(String title, String author, int year) { }

/** A stored book. */
public record Book(long id, String title, String author, int year) { }
```

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#bean -->
```java
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
```

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#describe-bean -->
```java
var reader = app.put("/readers/:name", ctx -> ctx.json(ctx.body(Reader.class)));
app.describe(reader, RouteDoc.summary("Save a reader").tags("readers").operationId("saveReader")
        .requestBody(Reader.class)
        .response(200, "The saved reader", Reader.class));
```

The property names in the schema are the names the JSON codec reads and writes, and a test
round-trips a record and a Lombok-shaped class through the real codec to keep them equal:

| Shape | Properties |
| --- | --- |
| Record | One per component, in declaration order |
| JavaBean | Public `getX()`, `isX()` (boolean only) and `setX(v)` methods and public fields, named like Jackson does (`getURL` is `url`, `getFirstName` is `firstName`), sorted by name |
| `@JsonProperty("n")`, `@JsonIgnore` | Honored when Jackson's annotations are on the class path (read by name, so this module does not depend on Jackson); `@JsonProperty(required = true)` makes the property required |
| Generic record or class | Needs a `RouteDoc.type(...)` with arguments; the schema is named after them (`PageOfNote`) |

A property is `required` when its type is a primitive (a bean needs a getter for this), when
`@JsonProperty(required = true)` says so, or when a supplied rule set says `notNull()`. An
`Optional` is never required. Records, beans and enums become named schemas under
`components.schemas` and are referenced with `$ref`, so a type that refers to itself, directly
or through others, is a reference and not an endless expansion. Nesting beyond 32 levels of
distinct types fails (raise it with `Builder.maxDepth`).

### Constraints from validation rules

Supply the same `Rules` you validate with and its built-in rules appear as schema constraints
(`notNull` as required, `length`/`size` as `minLength`/`maxLength`/`minItems`/`maxItems`,
`min`/`max`/`range` as `minimum`/`maximum`, `pattern`, `email`, `oneOf`). `each` rules constrain the items
of a list. Custom rules (`Rule.check`) cannot be described and are left out; the `pattern` is the
Java regular expression, copied as written. A rule that does not fit the property (a length on an
integer) or a property the type does not have fails generation instead of being ignored.

## Step 3: build the document and serve it

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#openapi -->
```java
var openApi = OpenApi.builder("Books API", "1.0.0")
        .description("Add and read books.")
        .securityScheme("bearer", SecurityScheme.bearer("JWT"))
        .rules(NewBook.class, NEW_BOOK)
        .defaults("/readers", RouteDoc.empty().tags("people"))
        .defaults("/books", RouteDoc.empty().response(500, "Unexpected server error"))
        .build();
```

`render(app)` returns the OpenAPI 3.1 JSON and `renderSwagger(app)` the Swagger 2.0 JSON, for a
file, a test or a build step. `serve(app, path, middleware...)` and
`serveSwagger(app, path, middleware...)` render the document **now** and register a GET route that
serves it, so register them after every other route:

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#serve -->
```java
if (docsAccess != null) {
    openApi.serve(app, "/openapi.json", docsAccess);        // OpenAPI 3.1
    openApi.serveSwagger(app, "/swagger.json", docsAccess); // Swagger 2.0
}
```

- Nothing is served unless you call `serve`; the route is not part of the document.
- The document is rendered once and cached for the life of the application, with
  `Content-Type: application/json`, `Cache-Control: no-cache` and a strong `ETag`
  (`If-None-Match` gets 304). `HEAD` and `OPTIONS` behave as for any route.
- A description that cannot be turned into a document fails `serve`, so startup stops instead of
  a request failing later.
- Output is deterministic: paths, methods, response codes and schema names are sorted, record
  properties keep their declaration order, so the same application gives the same bytes.
- The document names only what you described: no package or binary class name appears. Schemas
  are named by the simple class name; choose another with `Builder.schemaName(type, name)`.

## Step 4: group-level defaults

`Builder.defaults(pathPrefix, RouteDoc)` applies to every route whose path equals the prefix or
lies below it (`""` means all routes). Tags are added to the route's own tags, security applies
only to routes that declare none, and responses and parameters are added where the route has no
entry with the same status or name. Defaults apply in the order added. In the example, every
`/books` route also documents a 500 response and every `/readers` route gets the tag `people`.

## Step 5: protect the documentation, or leave it out

The document and any UI describe your API surface. Whether to expose them publicly is the
application's choice: Axiom registers nothing unless you call `serve`, and `serve` takes the same
middleware as any route, so every policy of `axiom-security` applies.

<!-- snippet: examples/openapi/src/main/java/example/openapi/DocsAccess.java#protect -->
```java
/**
 * Returns middleware that answers 401 unless the request carries the given key.
 *
 * @param expectedKey the key, read from configuration, never a literal in source
 * @return middleware for {@code serve(app, path, middleware)}
 */
public static Middleware requireKey(String expectedKey) {
    var expected = expectedKey.getBytes(StandardCharsets.UTF_8);
    Authenticator authenticator = new Authenticator() {
        @Override public Optional<SecurityIdentity> authenticate(Request request) {
            var sent = request.headers().get("X-Docs-Key");
            if (sent == null) {
                return Optional.empty();
            }
            if (!MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expected)) {
                throw new UnauthorizedException(challenge(), "invalid_docs_key");
            }
            return Optional.of(new SecurityIdentity("docs", Set.of("docs"), Set.of()));
        }

        @Override public String challenge() { return "X-Docs-Key realm=\"docs\""; }
    };
    return Security.of(authenticator).hasRole("docs");
}
```

Pass `DocsAccess.requireKey(key)` to `serve`/`serveSwagger` to answer 401 without the key, or use
`Security.of(jwt).hasRole("docs")` the same way. To turn the documentation off in production, do not
register it: the example's `create(null)` serves no documentation routes at all, and its `main`
registers them only when the environment variable `DOCS_KEY` is set. A document that was never
registered cannot be requested, so there is nothing to disable at runtime.

## Errors at build time

Generation fails with `IllegalArgumentException` naming the route and the part of it that is
wrong. These are the messages you will see:

| Cause | Message |
| --- | --- |
| A type with no schema (`Object`, an interface, a JDK class) | `POST /notes: Cannot describe request body: Object is not a supported type; use a record, a concrete JavaBean class, an enum, a collection, a map with String keys or a scalar` |
| Such a type inside another | `GET /r: Cannot describe Holder.value: Runnable is not a supported type; ...` |
| A generic class without arguments | `GET /p: Cannot describe response 200: the generic type Page is used without type arguments; describe it with a ParameterizedType` |
| A parameter that is not simple | `GET /n: Cannot describe parameter 'q': a parameter must be a scalar, an enum or a list of those, not Note` |
| Nesting beyond the depth bound | `GET /d: Cannot describe D2.next: types are nested deeper than 32 levels; raise Builder.maxDepth if intended` |
| Two classes with one simple name | `Cannot describe ...: both A and B would be named 'Same'; give one a different name with Builder.schemaName` |
| An unknown security scheme | `Cannot describe GET /x: it requires the security scheme 'nope', which is not defined; add it with Builder.securityScheme` |
| A duplicated operation id | `Cannot describe GET /y: the operation id 'same' is already used by GET /x` |
| A path parameter the template lacks | `Cannot describe GET /x/:id: it documents the path parameter 'other', which the route template does not capture` |
| A method OpenAPI cannot express | `Cannot describe PROPFIND /dav: OpenAPI cannot express the method PROPFIND; hide the route with RouteDoc.empty().hidden()` |
| A rule that does not fit | `Cannot describe Constrained.age: the LENGTH rule cannot constrain a value of type integer` |

`describe` itself throws `IllegalArgumentException` for a route that is not registered and
`IllegalStateException` after startup.

## Swagger 2.0

`renderSwagger`/`serveSwagger` produce the same information in the older format from the same
model: `definitions` instead of `components.schemas`, the body as an `in: body` parameter with
`consumes`, `produces` per operation, parameters with inline types, and `host`, `basePath` and
`schemes` from the first configured server. A bearer scheme becomes an API key in the
`Authorization` header, because Swagger 2.0 has no bearer type.

## Non-goals

These are decisions, not gaps: the generator is a small, dependency-free description of what you
told it.

- OpenAPI 3.0 output. The two formats are 3.1 and Swagger 2.0.
- YAML output. Both documents are JSON.
- Code generation of clients or servers, and validating requests against the document.
- Annotations or classpath scanning to find routes or schemas.
- Polymorphic types (`oneOf`, `anyOf`, discriminators); a type that is an interface or abstract
  class has no schema.
- Webhooks, callbacks, links, examples, and OAuth2 or OpenID Connect security schemes. Schemes
  are bearer, basic and API keys in a header or query.
- In Swagger 2.0: more than one server (the first is used), more than one body schema per
  operation, and schema constraints that format cannot express.
