package com.hms.common.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryPatternsTest {

    @Test
    void blankTermMatchesEverything() {
        assertThat(QueryPatterns.contains(null)).isEqualTo("%");
        assertThat(QueryPatterns.contains("")).isEqualTo("%");
        assertThat(QueryPatterns.contains("   ")).isEqualTo("%");
    }

    @Test
    void termBecomesALowerCasedContainsPattern() {
        assertThat(QueryPatterns.contains("  Nair ")).isEqualTo("%nair%");
    }

    @Test
    void userTypedWildcardsAreEscapedRatherThanHonoured() {
        // Searching for "50%" must look for the literal text, not "starts with 50".
        assertThat(QueryPatterns.contains("50%")).isEqualTo("%50\\%%");
        assertThat(QueryPatterns.contains("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void exactOrAnyPassesTheValueThroughOrMatchesAll() {
        assertThat(QueryPatterns.exactOrAny(" CARD ")).isEqualTo("CARD");
        assertThat(QueryPatterns.exactOrAny(null)).isEqualTo("%");
    }
}
