package io.axiom.validation;

import static io.axiom.validation.ValidationEndToEndTest.fields;
import static io.axiom.validation.ValidationEndToEndTest.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.context.BodyValidator;
import io.axiom.test.TestClient;
import io.axiom.validation.ValidationEndToEndTest.Signup;
import org.junit.jupiter.api.Test;

/** Validators passed to ctx.validatedBody, through TestClient and the real problem mapping. */
class ValidatedBodyTest {
    static final Validator<Signup> RULES = Rules.of(Signup.class)
            .field("name", Signup::name, Rule.notBlank(), Rule.maxLength(10))
            .field("email", Signup::email, Rule.notNull(), Rule.email());

    @Test
    void validatorsAreBodyValidators() throws Exception {
        BodyValidator<Signup> asBodyValidator = RULES;
        assertThat(asBodyValidator.validate(new Signup("ada", "ada@example.org"))).isEmpty();
        assertThat(Validation.require(RULES.and(ValidationEndToEndTest.SIGNUP), new Signup("ada", "ada@example.org")))
                .isEqualTo(new Signup("ada", "ada@example.org"));
    }

    @Test
    void answersInvalidBodiesWith422WithoutEchoingInput() throws Exception {
        var app = Axiom.create();
        app.post("/signups", ctx -> {
            var signup = ctx.validatedBody(Signup.class, RULES.and(ValidationEndToEndTest.SIGNUP));
            return ctx.status(201).text("created " + signup.name());
        });
        try (var client = TestClient.start(app)) {
            var created = client.post("/signups", SignupCodec.MEDIA_TYPE, "ada|ada@example.org");
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.body()).isEqualTo("created ada");

            var invalid = client.post("/signups", SignupCodec.MEDIA_TYPE, "POISON<script>-too-long|POISON@");
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(fields(invalid)).containsExactly("name", "email");
            assertThat(text(invalid)).contains("\"code\":\"validation_failed\"")
                    .doesNotContain("POISON", "script", "example.org");

            assertThat(client.post("/signups", "text/plain", "ada|ada@example.org").status()).isEqualTo(415);
        }
    }
}
