package com.jsgalactic.axiom.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.error.Violation;
import org.junit.jupiter.api.Test;

class FieldPathTest {
    @Test
    void joinsPropertiesAndIndexes() {
        var path = FieldPath.root().property("order").property("items").index(2).property("name");
        assertThat(path.toField()).isEqualTo("order.items[2].name");
        assertThat(path.isRoot()).isFalse();
        assertThat(path.isTruncated()).isFalse();
        assertThat(new Violation(path.toField(), "size").field()).isEqualTo("order.items[2].name");
    }

    @Test
    void reportsTheRootAsAnEmptyFieldSoObjectLevelViolationsStayValid() {
        assertThat(FieldPath.root().isRoot()).isTrue();
        assertThat(FieldPath.root().toField()).isEqualTo(FieldPath.ROOT).isEmpty();
        assertThat(new Violation(FieldPath.root().toField(), "ordered_range").field()).isEmpty();
        assertThat(FieldPath.root().index(3).toField()).isEmpty();
        assertThat(FieldPath.root().index(3).isTruncated()).isTrue();
    }

    @Test
    void rejectsNamesThatAreNotSafePropertySegments() {
        for (var name : new String[] {"", "1abc", "a.b", "a[0]", "na me", "ünïcode", "a$b", "x\n"}) {
            assertThatThrownBy(() -> FieldPath.root().property(name)).isInstanceOf(IllegalArgumentException.class);
            assertThat(FieldPath.isPropertyName(name)).isFalse();
        }
        assertThatThrownBy(() -> FieldPath.root().property(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FieldPath.root().property("a").index(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(FieldPath.isPropertyName("_a-b9")).isTrue();
    }

    @Test
    void stopsAtTheLastAncestorThatFitsTheFieldLimit() {
        var path = FieldPath.root();
        for (int i = 0; i < 40; i++) {
            path = path.property("segment" + i);
        }
        assertThat(path.isTruncated()).isTrue();
        assertThat(path.toField().length()).isLessThanOrEqualTo(FieldPath.MAX_LENGTH);
        assertThat(path.toField()).startsWith("segment0.segment1.").doesNotContain("segment39");
        var stopped = path.toField();
        assertThat(path.property("x").index(1).toField()).isEqualTo(stopped);
        new Violation(stopped, "size");
    }

    @Test
    void stopsAtIndexesTooLargeForAViolationField() {
        assertThat(FieldPath.root().property("a").index(999_999_999).toField()).isEqualTo("a[999999999]");
        var big = FieldPath.root().property("a").index(1_000_000_000);
        assertThat(big.toField()).isEqualTo("a");
        assertThat(big.isTruncated()).isTrue();
    }

    @Test
    void appendsRelativeFieldsFromNestedValidators() {
        var base = FieldPath.root().property("items").index(0);
        assertThat(base.append("name").toField()).isEqualTo("items[0].name");
        assertThat(base.append("tags[1]").toField()).isEqualTo("items[0].tags[1]");
        assertThat(base.append(FieldPath.ROOT).toField()).isEqualTo("items[0]");
        assertThat(FieldPath.root().append("name").toField()).isEqualTo("name");
        assertThatThrownBy(() -> base.append("bad field")).isInstanceOf(IllegalArgumentException.class);
        var longName = "n".repeat(250);
        assertThat(base.append(longName).toField()).isEqualTo("items[0]");
        assertThat(base.append(longName).isTruncated()).isTrue();
    }
}
