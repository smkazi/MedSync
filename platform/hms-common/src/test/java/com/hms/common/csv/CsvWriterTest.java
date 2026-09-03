package com.hms.common.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    @DisplayName("a plain row is written unquoted, CRLF-terminated")
    void plainRow() {
        String csv = new CsvWriter(List.of("a", "b")).row(List.of("1", "2")).toCsv();

        assertThat(csv).isEqualTo("a,b\r\n1,2\r\n");
    }

    @Test
    @DisplayName("commas, quotes and newlines are quoted and doubled per RFC 4180")
    void escaping() {
        String csv = new CsvWriter(List.of("detail"))
                .row(List.of("has, a comma"))
                .row(List.of("has \"quotes\""))
                .row(List.of("has\na newline"))
                .toCsv();

        assertThat(csv).isEqualTo("detail\r\n"
                + "\"has, a comma\"\r\n"
                + "\"has \"\"quotes\"\"\"\r\n"
                + "\"has\na newline\"\r\n");
    }

    @Test
    @DisplayName("a field a spreadsheet would execute is neutralised, not stripped")
    void formulaInjection() {
        // The attack this exists for: `detail` carries operator-supplied text, and a spreadsheet
        // executes a leading =, +, -, @, tab or CR. Quoting does not help -- the quotes are CSV
        // syntax and are gone before the spreadsheet reads the first character.
        for (String hostile : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tvalue")) {
            assertThat(CsvWriter.field(hostile))
                    .as("neutralised: %s", hostile.replace("\t", "\\t"))
                    .isEqualTo("'" + hostile);
        }

        // A leading carriage return is both a formula starter and a character RFC 4180 requires the
        // field to be quoted for, so the apostrophe lands inside the quotes. Worth asserting
        // exactly: get the order wrong and the quote character is what the spreadsheet sees first.
        assertThat(CsvWriter.field("\rvalue")).isEqualTo("\"'\rvalue\"");

        // The classic payload, which is a comma away from being quoted too.
        assertThat(CsvWriter.field("=HYPERLINK(\"http://evil\",\"click\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"");

        // Neutralised, never altered: the original characters are all still there, because an
        // audit export whose contents were silently cleaned up is one nobody can rely on.
        assertThat(CsvWriter.field("=1+1")).isEqualTo("'=1+1");
    }

    @Test
    @DisplayName("a value that merely contains an operator is left alone")
    void onlyTheFirstCharacterMatters() {
        assertThat(CsvWriter.field("family 1=2 revoked")).isEqualTo("family 1=2 revoked");
        assertThat(CsvWriter.field("a@b.local")).isEqualTo("a@b.local");
    }

    @Test
    @DisplayName("a null field is an empty field, not the string null")
    void nullsAreEmpty() {
        assertThat(CsvWriter.field(null)).isEmpty();
    }
}
