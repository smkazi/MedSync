package com.hms.patient.label;

import com.hms.common.barcode.BarcodeSvg;
import com.hms.patient.domain.Patient;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Renders a patient's wristband as SVG.
 *
 * <p>This closes the one manual step in the closed medication loop. The eMAR already refuses a dose
 * whose scanned wristband does not match the prescription's MRN — and nothing printed a wristband,
 * so in practice somebody typed the MRN off the chart. Typing is checked identically and it is not
 * the same control: the point of scanning is that the identifier comes off the patient rather than
 * off the paperwork the nurse is already looking at, which is exactly the confusion the check
 * exists to catch.
 *
 * <p><strong>The barcode payload is the MRN, and nothing else.</strong> That is not a formatting
 * choice: {@code AdministrationService} compares the scan to {@code prescriptions.patient_mrn} after
 * trimming and upper-casing it, so anything else on the band — an internal id, a prefixed string, a
 * URL — would scan as a mismatch and refuse every dose. Whoever changes this should change that
 * comparison in the same commit or not at all.
 *
 * <p><strong>Identity is the entire point of this label, which is the opposite of the tube label's
 * rule.</strong> {@code SpecimenLabelRenderer} deliberately carries no name and no MRN, because a
 * tube leaves the building and is handled by couriers. A wristband is on the wrist of the person it
 * identifies: name, MRN, date of birth and sex are what make it work, and a band carrying only a
 * barcode would be unreadable in exactly the situation that matters — a scanner that will not read,
 * with a nurse holding a syringe. Two opposite rules, so two renderers, in the two services that
 * own their content.
 *
 * <p>It lives in patient-service and not in admissions-service, which is a correction to what the
 * README predicted. A wristband is printed at admission, so that is where it looked like it
 * belonged — but every field on it is owned here, admissions-service holds none of them, and it
 * would have needed a cross-service client to fetch data the owning service can serve directly.
 * Putting it here also means casualty and the outpatient desk can print a band, and only in-patients
 * get admitted.
 *
 * <p>No allergy marker, and that is deliberate rather than forgotten. A red band is a real
 * convention and this platform cannot print colour it can guarantee; a monochrome "ALLERGY" line
 * that is sometimes present and sometimes not teaches staff to read a band for the absence of a
 * warning, which is the one thing a wristband must never be trusted for. The allergy check that
 * refuses a prescription is server-side, where it cannot be smudged.
 */
@Component
public class WristbandRenderer {

    /**
     * Bar height, shorter than the tube label's.
     *
     * <p>A band wraps around a wrist, so the symbol is read across a curve: too tall and the outer
     * rows of the scan cross the bars at an angle. Shorter and wider scans more reliably here, which
     * is the opposite trade-off from a flat tube label.
     */
    private static final int BAR_HEIGHT = 40;

    private static final int NAME_HEIGHT = 26;
    private static final int DETAIL_HEIGHT = 22;
    private static final int MRN_HEIGHT = 24;
    private static final int PADDING = 8;

    private static final int NAME_FONT_SIZE = 17;
    private static final int DETAIL_FONT_SIZE = 13;
    private static final int MRN_FONT_SIZE = 15;

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final String INK_COLOUR = "#000000";
    private static final String GROUND_COLOUR = "#ffffff";

    /** ISO, because a band may be read by somebody who writes dates the other way round. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final XMLOutputFactory XML = XMLOutputFactory.newFactory();

    public String render(Patient patient) {
        String mrn = patient.getMrn();
        BarcodeSvg symbol = BarcodeSvg.of(mrn, BAR_HEIGHT);

        int svgWidth = symbol.width();
        int svgHeight = PADDING + NAME_HEIGHT + DETAIL_HEIGHT + symbol.height() + MRN_HEIGHT
                + PADDING;

        StringWriter out = new StringWriter(1024);
        try {
            XMLStreamWriter svg = XML.createXMLStreamWriter(out);
            // No XML declaration: this markup is both served as image/svg+xml and inlined into an
            // HTML page for printing, and a declaration mid-document is invalid in the second case.
            svg.writeStartElement("svg");
            svg.writeAttribute("xmlns", SVG_NAMESPACE);
            svg.writeAttribute("width", Integer.toString(svgWidth));
            svg.writeAttribute("height", Integer.toString(svgHeight));
            svg.writeAttribute("viewBox", "0 0 " + svgWidth + " " + svgHeight);
            svg.writeAttribute("role", "img");
            // The accessible name carries the MRN rather than the patient's name: this markup is
            // inlined into a page, and a screen reader announcing a name is a disclosure in a
            // shared room. The name is on the band because a person has to read it; it does not
            // need to be read out twice.
            svg.writeAttribute("aria-label", "Wristband for " + mrn);

            svg.writeEmptyElement("rect");
            svg.writeAttribute("width", "100%");
            svg.writeAttribute("height", "100%");
            svg.writeAttribute("fill", GROUND_COLOUR);

            int y = PADDING;
            text(svg, svgWidth / 2, y + NAME_FONT_SIZE, NAME_FONT_SIZE, "sans-serif", "bold",
                    patient.fullName());
            y += NAME_HEIGHT;

            text(svg, svgWidth / 2, y + DETAIL_FONT_SIZE, DETAIL_FONT_SIZE, "sans-serif", null,
                    details(patient));
            y += DETAIL_HEIGHT;

            symbol.writeInto(svg, 0, y);
            y += symbol.height();

            // The MRN in print under the bars, for the scanner that will not read. This is the line
            // that makes a failed scan recoverable instead of a reason to skip the check.
            text(svg, svgWidth / 2, y + MRN_FONT_SIZE, MRN_FONT_SIZE, "monospace", null, mrn);

            svg.writeEndElement();
            svg.writeEndDocument();
            svg.flush();
            svg.close();
        } catch (XMLStreamException ex) {
            // Nothing here parses external input, so this cannot fail on data. Wrapped rather than
            // swallowed: a band that silently came back empty would be printed blank and worn.
            throw new IllegalStateException("Could not render the wristband for " + mrn, ex);
        }
        return out.toString();
    }

    /**
     * Date of birth and sex, the two identifiers a person checks a band against out loud.
     *
     * <p>Date of birth rather than age: age changes and a band does not, and "born 1971-04-02" is
     * checkable against what the patient says in a way "54 years" is not.
     */
    private static String details(Patient patient) {
        StringBuilder line = new StringBuilder();
        if (patient.getDateOfBirth() != null) {
            line.append("DOB ").append(DATE.format(patient.getDateOfBirth()));
        }
        if (patient.getSex() != null) {
            if (line.length() > 0) {
                line.append("  ·  ");
            }
            line.append(patient.getSex().name().charAt(0));
        }
        return line.toString();
    }

    private static void text(XMLStreamWriter svg, int x, int baseline, int fontSize, String family,
                             String weight, String content) throws XMLStreamException {
        svg.writeStartElement("text");
        svg.writeAttribute("x", Integer.toString(x));
        svg.writeAttribute("y", Integer.toString(baseline));
        svg.writeAttribute("text-anchor", "middle");
        svg.writeAttribute("font-family", family);
        svg.writeAttribute("font-size", Integer.toString(fontSize));
        if (weight != null) {
            svg.writeAttribute("font-weight", weight);
        }
        svg.writeAttribute("fill", INK_COLOUR);
        // Escaped by the writer, which is the reason this is an XMLStreamWriter and not a
        // StringBuilder: a patient's name is operator-supplied text and O'Brien & Sons is a name.
        svg.writeCharacters(content == null ? "" : content.trim());
        svg.writeEndElement();
    }
}
