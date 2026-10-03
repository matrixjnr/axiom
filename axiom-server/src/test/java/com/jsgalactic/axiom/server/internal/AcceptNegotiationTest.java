package com.jsgalactic.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
}
