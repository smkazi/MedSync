package com.hms.common.data;

import java.util.Locale;

/**
 * Builds the LIKE patterns used by the platform's search queries.
 *
 * <p>Optional filters are expressed as always-present patterns instead of nullable parameters. A
 * JPQL predicate of the form {@code (:value is null or ...)} sends an untyped null to PostgreSQL,
 * which infers {@code bytea} and then fails with "function lower(bytea) does not exist". Passing
 * {@code %} for "no filter" keeps every parameter a String and the query planner happy.
 */
public final class QueryPatterns {


    /** Matches anything. */
    public static final String ANY = "%";

    private QueryPatterns() {
    }

    /** A lower-cased {@code %term%} contains-pattern; blank input matches everything. */
    public static String contains(String term) {
        if (term == null || term.isBlank()) {
            return ANY;
        }
        // Locale.ROOT, not the default locale. Turkish lower-cases "I" to a dotless letter, so a
        // patient named IQBAL would stop matching a search for "iqbal" on a JVM started with
        // tr_TR - a bug that only ever appears on someone else's machine.
        return ANY + escape(term.trim().toLowerCase(Locale.ROOT)) + ANY;
    }

    /** The exact value to match, or {@code %} when no filter was supplied. */
    public static String exactOrAny(String value) {
        return value == null || value.isBlank() ? ANY : value.trim();
    }

    /** Neutralises LIKE wildcards typed by a user so a search for "50%" is not a wildcard search. */
    private static String escape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
