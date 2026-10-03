package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteDoc;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDocTest {
    record Note(String text) {}

    @Test void aDescriptionIsAttachedToItsRouteAndReadableAfterStartAndClose() {
        var app = Axiom.create();
        var documented = app.get("/notes/:id", ctx -> "x");
        var plain = app.get("/plain", ctx -> "x");
        var doc = RouteDoc.summary("Read a note").tags("notes", "notes", "public").operationId("readNote")
                .pathParam("id", String.class, "Note id").queryParam("v", int.class, null, false)
                .headerParam("X-Trace", String.class, null, false).response(200, "The note", Note.class)
                .response(404, "Missing").security("bearer").deprecated();
        app.describe(documented, doc);

        assertThat(app.doc(documented)).contains(doc);
        assertThat(app.doc(plain)).isEmpty();
        app.start();
        assertThat(app.doc(documented).orElseThrow().tags()).containsExactly("notes", "public");
        assertThatIllegalStateException().isThrownBy(() -> app.describe(plain, RouteDoc.empty()));
        app.close();
        assertThat(app.doc(documented)).contains(doc);
    }

    @Test void unknownRoutesAreRefused() {
        try (var app = Axiom.create()) {
            var unknown = new Route("GET", "/nope");
            assertThatIllegalArgumentException().isThrownBy(() -> app.describe(unknown, RouteDoc.empty()));
            assertThatIllegalArgumentException().isThrownBy(() -> app.doc(unknown));
        }
    }

    @Test void aRolledBackGroupDropsItsDescriptions() {
        try (var app = Axiom.create()) {
            try {
                app.group("/g", g -> {
                    app.describe(g.get("/a", ctx -> "x"), RouteDoc.summary("a"));
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException ignored) {
                // expected
            }
            var again = app.get("/g/a", ctx -> "x");
            assertThat(app.doc(again)).isEmpty();
        }
    }

    @Test void descriptionsValidateTheirParts() {
        var empty = RouteDoc.empty();
        assertThatIllegalArgumentException().isThrownBy(() -> empty.operationId("has space"));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.tags(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.security(""));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.response(99, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.response(200, "a").response(200, "b"));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.requestBody(Note.class, "not a type"));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.requestBody(Note.class, new String[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.pathParam("bad name", String.class, null));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.queryParam("a", int.class, null, true)
                .queryParam("a", int.class, null, true));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.headerParam("X-A", String.class, null, true)
                .headerParam("x-a", String.class, null, true));
        var doc = RouteDoc.summary("s").description("d").hidden()
                .requestBody(Note.class, "application/json", "application/json");
        assertThat(doc.requestBody().orElseThrow().mediaTypes()).isEqualTo(List.of("application/json"));
        assertThat(doc.isHidden()).isTrue();
        assertThat(doc.summary()).contains("s");
        assertThat(doc.description()).contains("d");
        assertThat(doc.isDeprecated()).isFalse();
        assertThat(doc.operationId()).isEmpty();
    }

    record Page<T>(List<T> items) {}

    @Test void genericTypesAreBuiltFromTheirArguments() {
        var type = (java.lang.reflect.ParameterizedType) RouteDoc.type(Page.class, Note.class);
        assertThat(type.getRawType()).isEqualTo(Page.class);
        assertThat(type.getActualTypeArguments()).containsExactly(Note.class);
        assertThat(type.getTypeName()).contains("Page<").contains("Note>");
        assertThat(type.toString()).isEqualTo(type.getTypeName());
        assertThat(type.getOwnerType()).isEqualTo(RouteDocTest.class);
        assertThat(((java.lang.reflect.ParameterizedType) RouteDoc.listOf(String.class)).getRawType()).isEqualTo(List.class);
        assertThatIllegalArgumentException().isThrownBy(() -> RouteDoc.type(Page.class));
        assertThatIllegalArgumentException().isThrownBy(() -> RouteDoc.type(Note.class, String.class));
        assertThatIllegalArgumentException().isThrownBy(() -> RouteDoc.type(List.class, String.class, String.class));
    }
}
