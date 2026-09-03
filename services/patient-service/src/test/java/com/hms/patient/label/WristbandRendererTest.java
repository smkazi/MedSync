package com.hms.patient.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.common.barcode.BarcodeSvg;
import com.hms.common.barcode.Code128;
import com.hms.common.error.BadRequestException;
import com.hms.patient.domain.Patient;
import com.hms.patient.domain.Sex;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What has to be true of a wristband, which is a shorter list than it looks and every item on it
 * is a bedside failure if it is wrong.
 */
class WristbandRendererTest {

    private final WristbandRenderer renderer = new WristbandRenderer();

    private static Patient patient(String mrn, String first, String last) {
        return new Patient(mrn, first, last, LocalDate.of(1971, 4, 2), Sex.FEMALE);
    }

    @Test
    @DisplayName("the barcode encodes the MRN, because that is what the eMAR compares it to")
    void barcodeCarriesTheMrn() {
        String svg = renderer.render(patient("MRN-2026-000042", "Asha", "Nair"));

        // The bars are read back out of the rendered SVG and compared to an independent encoding of
        // the MRN. Asserting that the markup merely contains the MRN would pass on the printed text
        // alone and prove nothing about the symbol — and the symbol is the half a scanner reads.
        // AdministrationService matches a scan to prescriptions.patient_mrn after trimming and
        // upper-casing, so anything else encoded here refuses every dose at the bedside.
        assertThat(barWidthsOf(svg)).isEqualTo(Code128.encode("MRN-2026-000042"));
    }

    @Test
    @DisplayName("the band carries the identity a person reads: name, date of birth, sex and the MRN")
    void bandCarriesIdentity() {
        String svg = renderer.render(patient("MRN-2026-000042", "Asha", "Nair"));

        // The exact opposite of the tube label's rule, and deliberately so: a tube leaves the
        // building, a band is on the wrist of the person it names.
        assertThat(svg).contains("Asha Nair");
        assertThat(svg).contains("1971-04-02");
        assertThat(svg).contains("MRN-2026-000042");
        // Date of birth rather than age, which changes while a band does not.
        assertThat(svg).doesNotContain("years");
    }

    @Test
    @DisplayName("the printed MRN survives a scanner that will not read")
    void mrnIsPrintedAsWellAsEncoded() {
        String svg = renderer.render(patient("MRN-2026-000042", "Asha", "Nair"));

        // Once inside a <text> element, and that is the point: a band with only bars is unreadable
        // in exactly the situation that matters — a failed scan, with a syringe in the other hand.
        assertThat(svg).contains(">MRN-2026-000042<");
    }

    @Test
    @DisplayName("a name with markup in it is escaped rather than emitted")
    void nameIsEscaped() {
        String svg = renderer.render(patient("MRN-2026-000043", "O'Brien & <script>", "Sons"));

        // The reason this renderer writes through an XMLStreamWriter. A name is operator-supplied
        // text, this markup is inlined into a page, and "O'Brien & Sons" is a name somebody has.
        assertThat(svg).doesNotContain("<script>");
        assertThat(svg).contains("&amp;");
    }

    @Test
    @DisplayName("the accessible name carries the MRN and not the patient's name")
    void ariaLabelDoesNotAnnounceTheName() {
        String svg = renderer.render(patient("MRN-2026-000042", "Asha", "Nair"));

        // A screen reader in a shared room announcing a patient's name is a disclosure. The name is
        // printed on the band because a person has to read it; it does not need to be read aloud.
        assertThat(svg).contains("aria-label=\"Wristband for MRN-2026-000042\"");
    }

    @Test
    @DisplayName("an MRN the symbology cannot encode is refused rather than mangled")
    void unencodableMrnIsRefused() {
        Patient patient = patient("MRN-2026- 000042", "Asha", "Nair");

        // Refused, not substituted: a band that scans as a different identifier is worse than one
        // that was never printed, because the check it defeats is the one at the bedside.
        assertThatThrownBy(() -> renderer.render(patient))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Code 128");
    }

    /**
     * Reads the symbol back out of the rendered SVG as Code 128 element widths.
     *
     * <p>Bars are drawn as rects and spaces are the gaps between them, so the element sequence is
     * recovered by walking the bars in order: each bar's own width, then the distance to the next
     * one. Dividing by the module width turns pixels back into modules, which is what the encoder
     * produced. The white ground is skipped because it is sized in percent rather than in units.
     *
     * <p>Deliberately not a check that the markup contains some expected string: this reconstructs
     * what a scanner would see, so it fails if the geometry is wrong as well as if the payload is.
     */
    private static int[] barWidthsOf(String svg) {
        Matcher rects = Pattern
                .compile("<rect x=\"(\\d+)\" y=\"\\d+\" width=\"(\\d+)\"")
                .matcher(svg);
        List<int[]> bars = new ArrayList<>();
        while (rects.find()) {
            bars.add(new int[] {Integer.parseInt(rects.group(1)), Integer.parseInt(rects.group(2))});
        }
        assertThat(bars).as("the band should carry a drawn symbol").isNotEmpty();

        List<Integer> elements = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            int[] bar = bars.get(i);
            elements.add(bar[1] / BarcodeSvg.MODULE_WIDTH);
            if (i + 1 < bars.size()) {
                elements.add((bars.get(i + 1)[0] - (bar[0] + bar[1])) / BarcodeSvg.MODULE_WIDTH);
            }
        }
        return elements.stream().mapToInt(Integer::intValue).toArray();
    }
}
