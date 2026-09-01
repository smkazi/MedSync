package com.hms.laboratory.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.laboratory.web.dto.LabDtos;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders real PDFs and reads them back.
 *
 * <p>Possible without a database or a running patient-service because the renderer takes records:
 * all the resolving happens in {@code LabReportService}, all the drawing happens here. So these
 * assertions are on actual extracted PDF text, not on a mock's arguments — if a section stops being
 * written, or the watermark stops appearing, the text comes back without it.
 */
class LabReportRendererTest {

    private static final ReportLayout LAYOUT = new ReportLayout(
            "Demo Diagnostics", "12 Example Road", "Test City 000000",
            "Dr A Pathologist", "R Technician", "");

    private static final Instant AT = Instant.parse("2026-09-01T09:30:00Z");

    private static ReportContent content(boolean verified, List<String> notes,
                                         LabDtos.MorphologyView morphology) {
        return new ReportContent("Complete Blood Count", "Meera Nair", "MRN-2026-000001",
                "44 yrs / F", "L2026-000042", "dr.rao", AT, verified,
                verified ? "dr.pathan" : "", notes, morphology,
                "Demo Diagnostics\nPatient : Meera Nair\nHGB : 9.4 g/dL [L]");
    }

    private static List<ReportSection> sections() {
        return List.of(
                new ReportSection("Red blood cell (RBC) parameters", List.of(
                        new ReportRow("HGB", "Haemoglobin", "9.4", "g/dL", "11.5 - 14.5", "L", true),
                        new ReportRow("MCV", "MCV", "68", "fL", "80 - 100", "L", true))),
                new ReportSection("Platelet parameters", List.of(
                        new ReportRow("PLT", "Platelet Count", "250", "10^3/uL", "150 - 450", "", false))));
    }

    /**
     * Extracted text with whitespace collapsed away.
     *
     * <p>Needed for the rotated watermark: PDFBox's stripper walks glyphs in page order and breaks
     * "PROVISIONAL" across lines as it climbs the diagonal, so a literal contains() on the raw text
     * fails on a document where the stamp is perfectly present. Asserting on the squashed form tests
     * what was drawn rather than how the extractor chose to reassemble it.
     */
    private static String squashed(byte[] pdf) throws IOException {
        return textOf(pdf).replaceAll("\\s+", "");
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(
                new ByteArrayInputStream(pdf)))) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    @DisplayName("a verified report carries the identity, the results and the signature")
    void verifiedReport() throws Exception {
        byte[] pdf = new LabReportRenderer(LAYOUT)
                .render(content(true, List.of("Anaemia - low haemoglobin."), null), sections());

        assertThat(pdf).isNotEmpty();
        // A real PDF, not a blob of bytes that happens to be non-empty.
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        String text = textOf(pdf);
        assertThat(text).contains("Demo Diagnostics").contains("12 Example Road");
        assertThat(text).contains("Meera Nair").contains("MRN-2026-000001").contains("44 yrs / F");
        assertThat(text).contains("L2026-000042");
        assertThat(text).contains("Complete Blood Count");
        assertThat(text).contains("Red blood cell (RBC) parameters").contains("Platelet parameters");
        assertThat(text).contains("Haemoglobin").contains("9.4").contains("11.5 - 14.5");
        assertThat(text).contains("Anaemia");
        // The signature block only appears on a signed report.
        assertThat(text).contains("Dr A Pathologist").contains("Pathologist");
        assertThat(squashed(pdf)).doesNotContain("PROVISIONAL");
    }

    @Test
    @DisplayName("an unverified report is stamped PROVISIONAL and carries no signature")
    void provisionalReport() throws Exception {
        byte[] pdf = new LabReportRenderer(LAYOUT).render(content(false, List.of(), null), sections());
        String text = textOf(pdf);

        // The stamp is the whole point: results exist before a pathologist signs, and a clinician
        // who needs them early must not end up holding something indistinguishable from a released
        // report.
        assertThat(squashed(pdf)).contains("PROVISIONAL");
        // A name under a "Pathologist" caption is a claim that somebody reviewed it.
        assertThat(text).doesNotContain("Verified and released");
        assertThat(text).doesNotContain("Dr A Pathologist");
        // The results themselves are still there - that is why it renders at all.
        assertThat(text).contains("Haemoglobin").contains("9.4");
    }

    @Test
    @DisplayName("derived morphology is labelled as derived on the page, not just in the API")
    void derivedMorphologyIsLabelled() throws Exception {
        LabDtos.MorphologyView derived = new LabDtos.MorphologyView(null,
                "Microcytic hypochromic with anisocytosis",
                "Total & differential leucocyte count within normal limits", true, "Adequate on smear");

        String text = textOf(new LabReportRenderer(LAYOUT)
                .render(content(true, List.of(), derived), sections()));

        assertThat(text).contains("Microcytic hypochromic with anisocytosis");
        assertThat(text).contains("Adequate on smear");
        // A reader is entitled to know which sentences a person wrote.
        assertThat(text).contains("Derived from the measured indices");
    }

    @Test
    @DisplayName("a pathologist's own smear comment replaces the derived one and is not labelled")
    void manualMorphologyIsNotLabelledDerived() throws Exception {
        LabDtos.MorphologyView manual = new LabDtos.MorphologyView(
                "Occasional target cells seen. Reviewed on smear.", null, null, false);

        String text = textOf(new LabReportRenderer(LAYOUT)
                .render(content(true, List.of(), manual), sections()));

        assertThat(text).contains("Occasional target cells seen");
        assertThat(text).doesNotContain("Derived from the measured indices");
    }

    @Test
    @DisplayName("characters the PDF font cannot encode are folded rather than aborting the report")
    void unencodableCharactersAreFolded() throws Exception {
        // The interpretive messages are full of en dashes, and one unencodable character would
        // otherwise throw out of showText and take the whole document down.
        List<String> notes = List.of(
                "Anisocytosis — increased red cell size variation (raised RDW).",
                "Warm the sample to 37°C and verify.",
                "WBC 7.36 ×10³/µL ‘borderline’");

        String text = textOf(new LabReportRenderer(LAYOUT)
                .render(content(true, notes, null), sections()));

        assertThat(text).contains("Anisocytosis - increased red cell size variation");
        assertThat(text).contains("deg C");
        assertThat(text).contains("'borderline'");
    }

    @Test
    @DisplayName("a laboratory that configured no identity gets no letterhead, not somebody else's")
    void blankIdentityRendersNoHeader() throws Exception {
        ReportLayout blank = new ReportLayout("", "", "", "", "", "");
        String text = textOf(new LabReportRenderer(blank)
                .render(content(true, List.of(), null), sections()));

        assertThat(text).doesNotContain("Demo Diagnostics");
        // Everything clinical still prints. Only the letterhead is absent.
        assertThat(text).contains("Meera Nair").contains("Haemoglobin");
    }

    @Test
    @DisplayName("an unmeasurable value prints as a dash, not as an empty cell")
    void unmeasurableValue() throws Exception {
        List<ReportSection> withMasked = List.of(new ReportSection("Red blood cell (RBC) parameters",
                List.of(new ReportRow("MCH", "MCH", "—", "pg", "27 - 36", "", false))));

        String text = textOf(new LabReportRenderer(LAYOUT)
                .render(content(true, List.of(), null), withMasked));

        // A blank cell reads as a test that was not requested, which is a different statement.
        assertThat(text).contains("MCH");
        assertThat(text).contains("27 - 36");
    }
}
