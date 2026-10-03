package com.jsgalactic.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AcceptNegotiationTest {
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // The most specific matching range decides, whatever its position.
            "application/json;q=0, */*             | false",
            "*/*, application/json;q=0             | false",
            "*/*;q=0.1, application/json           | true",
            "application/*;q=0, */*                | false",
            "application/*;q=0, application/json   | true",
            "*/*;q=0, application/*;q=0.5          | true",
            "*/*;q=0                               | false",
            "text/html, */*;q=0                    | false",
            "text/html                             | false",
            "APPLICATION/JSON                      | true",
            "application/json;q=0.001              | true",
            "application/json;q=0.000              | false",
            "application/json;q=1.000              | true",
            // Parameters: only charset=utf-8 matches the UTF-8 JSON representation.
            "application/json;charset=utf-8        | true",
            "application/json; charset=\"UTF-8\"     | true",
            "application/json;charset=iso-8859-1   | false",
            "application/json;charset=iso-8859-1, application/json;q=0.5 | true",
            "application/json;profile=x, */*;q=0   | false",
            "application/json;charset=utf-8;q=0, application/json | false",
            "application/json;q=0.5;ext=\"a,b\"     | true",
            // Equally specific ranges: the first listed wins.
            "application/json;q=0, application/json | false",
            "application/json, application/json;q=0 | true",
            // Empty list elements are allowed.
            "', , application/json'                | true",
            // Malformed headers are treated as absent.
            "garbage                               | true",
            "application/json;q=2                  | true",
            "application/json;q=abc, text/html     | true",
            "*/json                                | true",
            "application/json;charset=\"open      | true",
            "text/html;;q=0                        | true",
            "''                                    | true"
    })
    void negotiatesJsonAgainstAccept(String accept, boolean acceptable) {
        assertThat(Codecs.acceptable(accept, "application/json")).isEqualTo(acceptable);
    }

    /** Headers sent by common clients: JSON is acceptable for each of them unless they list only other types. */
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // curl, fetch, XMLHttpRequest and most HTTP libraries.
            "*/*                                                    | true",
            // axios.
            "application/json, text/plain, */*                      | true",
            // Browser navigations (Chrome, Edge, Firefox, Safari): parameters such as v=b3 and many ranges.
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7 | true",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8 | true",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8 | true",
            // Explicit preferences.
            "application/json;q=0.9, text/csv;q=1.0, */*;q=0.1      | true",
            "application/vnd.api+json, application/json             | true",
            "application/json, text/javascript, */*; q=0.01         | true",
            // Clients that want something else and say so.
            "text/html,application/xhtml+xml                        | false",
            "image/png, image/*;q=0.8                               | false",
            "text/event-stream                                      | false",
            "application/vnd.api+json                               | false",
    })
    void admitsHeadersSentByRealClients(String accept, boolean acceptable) {
        assertThat(Codecs.acceptable(accept, "application/json")).isEqualTo(acceptable);
    }

    private static String ranges(int count, String last) {
        var header = new StringBuilder();
        for (int i = 0; i < count - 1; i++) { header.append("text/x-").append(i).append(", "); }
        return header.append(last).toString();
    }

    @Test void parsesUpToSixtyFourRangesAndTreatsMoreAsAnAbsentHeader() {
        // With 64 ranges the last one still decides; with 65 the header is ignored, so JSON is admitted.
        assertThat(Codecs.parseAccept(ranges(64, "application/json;q=0"))).hasSize(64);
        assertThat(Codecs.acceptable(ranges(64, "application/json;q=0"), "application/json")).isFalse();
        assertThat(Codecs.acceptable(ranges(64, "text/x-last"), "application/json")).isFalse();
        assertThat(Codecs.acceptable(ranges(64, "application/json"), "application/json")).isTrue();

        assertThat(Codecs.parseAccept(ranges(65, "application/json;q=0"))).isNull();
        assertThat(Codecs.acceptable(ranges(65, "application/json;q=0"), "application/json")).isTrue();
        assertThat(Codecs.acceptable(ranges(65, "text/x-last"), "application/json")).isTrue();
        assertThat(Codecs.acceptable(ranges(1000, "application/json;q=0"), "application/json")).isTrue();
    }

    @Test void emptyListElementsAreNotCountedAsRanges() {
        var padding = ", ".repeat(200);
        assertThat(Codecs.acceptable("text/html" + padding + "application/json;q=0", "application/json")).isFalse();
        assertThat(Codecs.parseAccept("text/html" + padding + "application/json")).hasSize(2);
    }
}
