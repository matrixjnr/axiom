package com.jsgalactic.axiom.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.http.HttpStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class StatusExceptionTest {
    static Stream<Arguments> taxonomy() {
        return Stream.<Object[]>of(
                new Object[] {400, "bad_request", (Supplier<AxiomException>) BadRequestException::new},
                new Object[] {400, "malformed_body", (Supplier<AxiomException>) () -> new DecodeException("malformed_body")},
                new Object[] {401, "unauthorized", (Supplier<AxiomException>) () -> new UnauthorizedException("Bearer")},
                new Object[] {403, "forbidden", (Supplier<AxiomException>) ForbiddenException::new},
                new Object[] {404, "not_found", (Supplier<AxiomException>) NotFoundException::new},
                new Object[] {405, "method_not_allowed", (Supplier<AxiomException>) () -> new MethodNotAllowedException(Set.of("GET"))},
                new Object[] {406, "not_acceptable", (Supplier<AxiomException>) NotAcceptableException::new},
                new Object[] {408, "request_timeout", (Supplier<AxiomException>) RequestTimeoutException::new},
                new Object[] {409, "conflict", (Supplier<AxiomException>) ConflictException::new},
                new Object[] {410, "gone", (Supplier<AxiomException>) GoneException::new},
                new Object[] {411, "length_required", (Supplier<AxiomException>) LengthRequiredException::new},
                new Object[] {412, "precondition_failed", (Supplier<AxiomException>) PreconditionFailedException::new},
                new Object[] {413, "content_too_large", (Supplier<AxiomException>) PayloadTooLargeException::new},
                new Object[] {414, "uri_too_long", (Supplier<AxiomException>) UriTooLongException::new},
                new Object[] {415, "unsupported_media_type", (Supplier<AxiomException>) UnsupportedMediaTypeException::new},
                new Object[] {422, "unprocessable_content", (Supplier<AxiomException>) UnprocessableContentException::new},
                new Object[] {422, "validation_failed", (Supplier<AxiomException>) () -> new ValidationException(List.of())},
                new Object[] {428, "precondition_required", (Supplier<AxiomException>) PreconditionRequiredException::new},
                new Object[] {429, "too_many_requests", (Supplier<AxiomException>) TooManyRequestsException::new},
                new Object[] {431, "request_header_fields_too_large", (Supplier<AxiomException>) RequestHeaderFieldsTooLargeException::new},
                new Object[] {500, "internal_server_error", (Supplier<AxiomException>) InternalServerErrorException::new},
                new Object[] {501, "not_implemented", (Supplier<AxiomException>) NotImplementedException::new},
                new Object[] {502, "bad_gateway", (Supplier<AxiomException>) BadGatewayException::new},
                new Object[] {503, "service_unavailable", (Supplier<AxiomException>) ServiceUnavailableException::new},
                new Object[] {504, "gateway_timeout", (Supplier<AxiomException>) GatewayTimeoutException::new}
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("taxonomy")
    void eachExceptionHasItsStatusAndDefaultCode(int status, String code, Supplier<AxiomException> factory) {
        var failure = factory.get();
        assertThat(failure.status()).isEqualTo(status);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.getMessage()).isEqualTo(status + " " + code);
        assertThat(HttpStatus.reasonPhrase(status)).isNotBlank();
    }

    @Test void typedHeadersAreBuiltFromValidatedValues() {
        assertThat(new UnauthorizedException("Bearer realm=\"api\"").headers())
                .containsExactly(Map.entry("WWW-Authenticate", "Bearer realm=\"api\""));
        assertThat(new MethodNotAllowedException(Set.of("PUT", "GET")).headers()).containsEntry("Allow", "GET, PUT");
        assertThat(new TooManyRequestsException(Duration.ofMillis(1500)).headers()).containsEntry("retry-after", "2");
        assertThat(new ServiceUnavailableException(Duration.ZERO, "maintenance").headers())
                .containsEntry("Retry-After", "0");
        assertThat(new ServiceUnavailableException().headers()).isEmpty();
        assertThat(new NotFoundException("note_not_found").code()).isEqualTo("note_not_found");
    }

    @Test void rejectsHeaderInjectionAndUnsafeHeaderValues() {
        for (var challenge : new String[] {"Bearer\r\nSet-Cookie: x=1", "Bearer\nX: y", "", " Bearer", "Bearer é",
                "Bearer " + "x".repeat(1100), "Bearer\u0000"}) {
            assertThatIllegalArgumentException().as(challenge).isThrownBy(() -> new UnauthorizedException(challenge));
        }
        for (var method : new String[] {"GET\r\nX: y", "GET, POST", "", "G ET"}) {
            assertThatIllegalArgumentException().as(method).isThrownBy(() -> new MethodNotAllowedException(Set.of(method)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new MethodNotAllowedException(Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TooManyRequestsException(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ServiceUnavailableException(Duration.ofDays(2)));
    }

    @Test void statusPhrasesAndCodesFollowRfc9110() {
        assertThat(HttpStatus.reasonPhrase(HttpStatus.CONTENT_TOO_LARGE)).isEqualTo("Content Too Large");
        assertThat(HttpStatus.defaultCode(HttpStatus.HTTP_VERSION_NOT_SUPPORTED)).isEqualTo("http_version_not_supported");
        assertThat(HttpStatus.reasonPhrase(299)).isEqualTo("Success");
        assertThat(HttpStatus.reasonPhrase(599)).isEqualTo("Server Error");
        assertThatIllegalArgumentException().isThrownBy(() -> HttpStatus.reasonPhrase(100));
    }
}
