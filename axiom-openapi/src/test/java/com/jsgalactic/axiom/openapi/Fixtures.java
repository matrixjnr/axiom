package com.jsgalactic.axiom.openapi;

import static com.jsgalactic.axiom.validation.Rule.email;
import static com.jsgalactic.axiom.validation.Rule.maxLength;
import static com.jsgalactic.axiom.validation.Rule.notBlank;
import static com.jsgalactic.axiom.validation.Rule.notNull;
import static com.jsgalactic.axiom.validation.Rule.pattern;
import static com.jsgalactic.axiom.validation.Rule.range;
import static com.jsgalactic.axiom.validation.Rule.size;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.routing.RouteDoc;
import com.jsgalactic.axiom.validation.Rules;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Types and an application shared by the tests. */
final class Fixtures {
    private Fixtures() { }

    enum Priority { LOW, HIGH }

    record NewNote(String title, String body, List<String> tags, Priority priority) { }

    record Note(UUID id, String title, List<String> tags, Priority priority, Optional<String> extra, int version,
                Instant created, LocalDate due, Map<String, Integer> scores) { }

    record Node(String name, List<Node> children, Node parent) { }

    record Page<T>(List<T> items, long total) { }

    /** Shaped exactly like Lombok's output for {@code @Data}: accessors, equals, hashCode, toString. */
    static class Account {
        private String name;
        private boolean active;
        private Boolean verified;
        private String URL;
        private List<String> roles;
        private int loginCount;

        public Account() { }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Boolean getVerified() { return verified; }
        public void setVerified(Boolean verified) { this.verified = verified; }
        public String getURL() { return URL; }
        public void setURL(String url) { this.URL = url; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
        public int getLoginCount() { return loginCount; }
        public void setLoginCount(int loginCount) { this.loginCount = loginCount; }

        protected boolean canEqual(Object other) { return other instanceof Account; }

        @Override public boolean equals(Object o) {
            return o instanceof Account a && a.canEqual(this) && active == a.active && loginCount == a.loginCount
                    && Objects.equals(name, a.name) && Objects.equals(verified, a.verified)
                    && Objects.equals(URL, a.URL) && Objects.equals(roles, a.roles);
        }

        @Override public int hashCode() { return Objects.hash(name, active, verified, URL, roles, loginCount); }

        @Override public String toString() { return "Fixtures.Account(name=" + name + ")"; }
    }

    static Type listOf(Type element) { return RouteDoc.listOf(element); }

    static Type parameterized(Class<?> raw, Type... arguments) { return RouteDoc.type(raw, arguments); }

    static final Rules<NewNote> NEW_NOTE_RULES = Rules.of(NewNote.class)
            .field("title", NewNote::title, notNull(), notBlank(), maxLength(80))
            .field("body", NewNote::body, pattern("[a-z ]*"))
            .each("tags", NewNote::tags, maxLength(16))
            .field("tags", NewNote::tags, size(0, 5));

    static final Rules<Account> ACCOUNT_RULES = Rules.of(Account.class)
            .field("name", Account::getName, notNull(), email())
            .field("loginCount", Account::getLoginCount, range(0, 1000));

    /** A small notes API; every route is described except the last. */
    static Application notesApi() {
        var app = Axiom.create();
        app.describe(app.get("/notes", ctx -> "x"), RouteDoc.summary("List notes").description("Newest first.")
                .tags("notes").operationId("listNotes")
                .queryParam("limit", int.class, "Page size", false)
                .queryParam("tag", listOf(String.class), "Filter by tags", false)
                .headerParam("X-Request-Id", UUID.class, null, false)
                .response(200, "A page of notes", parameterized(Page.class, Note.class)));
        app.describe(app.post("/notes", ctx -> "x"), RouteDoc.summary("Create a note").tags("notes")
                .operationId("createNote").requestBody(NewNote.class, "application/json", "application/cbor")
                .response(201, "Created", Note.class).response(422, "Validation failed")
                .security("bearer"));
        app.describe(app.get("/notes/:id", ctx -> "x"), RouteDoc.summary("Read a note").tags("notes")
                .operationId("readNote").pathParam("id", UUID.class, "Note id")
                .response(200, "The note", Note.class).response(404, "No such note"));
        app.describe(app.delete("/notes/:id", ctx -> "x"), RouteDoc.summary("Delete a note").tags("notes")
                .deprecated().security("bearer", "key").response(204, "Deleted"));
        app.describe(app.get("/tree", ctx -> "x"), RouteDoc.summary("A cyclic structure")
                .response(200, "The tree", Node.class));
        app.describe(app.get("/accounts/:name", ctx -> "x"), RouteDoc.summary("Read an account")
                .response(200, "The account", Account.class));
        app.describe(app.get("/internal/ping", ctx -> "x"), RouteDoc.empty().hidden());
        app.get("/files/*path", ctx -> "x");
        app.head("/notes", ctx -> "x");
        return app;
    }

    static OpenApi notesConfig() {
        return OpenApi.builder("Notes API", "1.2.0").description("Sample.")
                .server("https://api.example.com/v1", "Production")
                .securityScheme("bearer", SecurityScheme.bearer("JWT"))
                .securityScheme("key", SecurityScheme.apiKeyHeader("X-Api-Key"))
                .rules(NewNote.class, NEW_NOTE_RULES).rules(Account.class, ACCOUNT_RULES)
                .defaults("/notes", RouteDoc.empty().response(500, "Server error"))
                .build();
    }
}
