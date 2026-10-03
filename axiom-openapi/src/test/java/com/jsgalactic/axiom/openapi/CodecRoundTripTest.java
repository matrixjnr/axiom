package com.jsgalactic.axiom.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Sends and receives a record and Lombok-shaped classes through the real JSON codec and checks
 * that the property names the codec writes and accepts are exactly those of the generated schema.
 */
class CodecRoundTripTest {
    record Flat(String name, int count, boolean flag, List<String> tags, Optional<String> note) { }

    /** Lombok {@code @Value}-like read side plus {@code @Setter}: getters and setters, boolean "is". */
    static class Person {
        private String firstName;
        private boolean admin;
        private Boolean subscribed;
        private String url;
        private int httpCode;
        private String secret;
        private String internalName;

        public String getFirstName() { return firstName; }
        public void setFirstName(String value) { this.firstName = value; }
        public boolean isAdmin() { return admin; }
        public void setAdmin(boolean value) { this.admin = value; }
        public Boolean getSubscribed() { return subscribed; }
        public void setSubscribed(Boolean value) { this.subscribed = value; }
        public String getURL() { return url; }
        public void setURL(String value) { this.url = value; }
        public int getHTTPCode() { return httpCode; }
        public void setHTTPCode(int value) { this.httpCode = value; }
        @JsonIgnore public String getSecret() { return secret; }
        public void setSecret(String value) { this.secret = value; }
        @JsonProperty("display_name") public String getInternalName() { return internalName; }
        public void setInternalName(String value) { this.internalName = value; }
    }

    record Renamed(@JsonProperty("full_name") String name, @JsonIgnore String hidden, int plain) { }

    private static final Pattern KEY = Pattern.compile("\"([^\"]+)\":");

    private static Set<String> keys(String json) {
        var out = new LinkedHashSet<String>();
        var matcher = KEY.matcher(json);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> schemaProperties(Class<?> type) {
        var schemas = new Schemas(8, Map.of(), Map.of());
        schemas.schema(type, "test");
        var schema = schemas.components().get(type.getSimpleName());
        return new LinkedHashSet<>(((Map<String, Object>) schema.get("properties")).keySet());
    }

    private static String sampleBody(Class<?> type) {
        var schemas = new Schemas(8, Map.of(), Map.of());
        schemas.schema(type, "test");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Map<String, Object>>) schemas.components().get(type.getSimpleName()).get("properties");
        var members = new ArrayList<String>();
        properties.forEach((name, schema) -> members.add("\"" + name + "\":" + switch ((String) schema.get("type")) {
            case "integer" -> "1";
            case "number" -> "1.5";
            case "boolean" -> "true";
            case "array" -> "[\"x\"]";
            default -> "\"x\"";
        }));
        return "{" + String.join(",", members) + "}";
    }

    private static <T> void assertAgrees(Class<T> type) throws Exception {
        var app = Axiom.create();
        app.post("/echo", ctx -> ctx.json(ctx.body(type)));
        try (var client = TestClient.start(app)) {
            var sent = sampleBody(type);
            var reply = client.post("/echo", "application/json", sent);
            // The codec accepts every property of the schema (unknown ones would be refused) ...
            assertThat(reply.status()).as(String.valueOf(reply.body())).isEqualTo(200);
            // ... and writes exactly those names.
            var written = new String(reply.body() instanceof byte[] bytes ? bytes
                    : String.valueOf(reply.body()).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertThat(keys(written)).containsExactlyInAnyOrderElementsOf(schemaProperties(type));
            // A property the schema does not list is refused by the strict codec.
            var unknown = client.post("/echo", "application/json", sent.replaceFirst("\\{", "{\"zz_unknown\":1,"));
            assertThat(unknown.status()).isEqualTo(400);
        }
    }

    @Test void aRecordAgreesWithTheCodec() throws Exception {
        assertAgrees(Flat.class);
        assertThat(schemaProperties(Flat.class)).containsExactly("name", "count", "flag", "tags", "note");
    }

    @Test void aLombokShapedClassAgreesWithTheCodec() throws Exception {
        assertAgrees(Fixtures.Account.class);
        assertThat(schemaProperties(Fixtures.Account.class))
                .containsExactlyInAnyOrder("name", "active", "verified", "url", "roles", "loginCount");
    }

    @Test void jacksonRenamesAndIgnoresAreHonoredLikeTheCodecDoes() throws Exception {
        assertAgrees(Person.class);
        assertThat(schemaProperties(Person.class))
                .containsExactlyInAnyOrder("firstName", "admin", "subscribed", "url", "httpcode", "display_name")
                .doesNotContain("secret");
        assertAgrees(Renamed.class);
        assertThat(schemaProperties(Renamed.class)).containsExactly("full_name", "plain");
    }
}
