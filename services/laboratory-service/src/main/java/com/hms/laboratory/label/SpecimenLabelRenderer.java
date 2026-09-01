package com.hms.laboratory.label;

import java.io.StringWriter;
import java.util.Locale;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Renders a specimen label as SVG.
 *
 * <p>SVG rather than PNG because a label is printed, and a barcode rasterised to a fixed pixel grid
 * and then scaled by the printer driver is the classic cause of a symbol that will not scan. Vector
 * output means the bars keep their widths relative to each other at whatever size the printer picks.
 *
 * <p>Written through {@link XMLStreamWriter} rather than by appending to a {@code StringBuilder}.
 * The first version did the latter with its own escaping helper, and FindSecBugs flagged it
 * ({@code POTENTIAL_XML_INJECTION}) — correctly, not as a false positive. The escaping was right on
 * the day it was written, but correctness that depends on every future caller remembering to call
 * one helper is not correctness. A writer escapes attributes and text as a property of the API, so
 * the class can no longer emit malformed or injected markup even if someone adds a field to it.
 *
 * <p>Deliberately narrow content: the accession number as a barcode, the same number as text, and
 * the specimen type. <strong>No patient name and no MRN.</strong> A tube label is handled by
 * couriers and visible to other patients in a shared collection room, and the laboratory works by
 * accession number — a name would leak identity from the one artefact guaranteed to leave the
 * building, and buy the lab nothing.
 */
@Component
public class SpecimenLabelRenderer {

    /** Printed width of one narrow element. Two units keeps the symbol readable by cheap scanners. */
    private static final int MODULE_WIDTH = 2;

    /** Bar height. Tall enough that a slightly skewed scan still crosses the whole symbol. */
    private static final int BAR_HEIGHT = 56;

    /**
     * Quiet zone, in modules.
     *
     * <p>Ten, the specification's minimum. Scanners need blank space to find the symbol's edges; a
     * label trimmed flush to the first bar reads intermittently, which is worse than one that never
     * reads at all because nobody investigates it.
     */
    private static final int QUIET_ZONE_MODULES = 10;

    private static final int TEXT_HEIGHT = 30;
    private static final int TEXT_BASELINE_OFFSET = 20;
    private static final int FONT_SIZE = 16;

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final String BAR_COLOUR = "#000000";
    private static final String GROUND_COLOUR = "#ffffff";

    private static final XMLOutputFactory XML = XMLOutputFactory.newFactory();

    public String render(String accessionNo, String specimenType) {
        int[] widths = Code128.encode(accessionNo);

        int symbolModules = 0;
        for (int width : widths) {
            symbolModules += width;
        }
        int svgWidth = (symbolModules + 2 * QUIET_ZONE_MODULES) * MODULE_WIDTH;
        int svgHeight = BAR_HEIGHT + TEXT_HEIGHT;

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
            svg.writeAttribute("aria-label", "Specimen " + accessionNo);

            // White ground, explicitly. A transparent background printed onto anything but white
            // paper changes the contrast the scanner depends on.
            svg.writeEmptyElement("rect");
            svg.writeAttribute("width", "100%");
            svg.writeAttribute("height", "100%");
            svg.writeAttribute("fill", GROUND_COLOUR);

            int x = QUIET_ZONE_MODULES * MODULE_WIDTH;
            boolean bar = true;
            for (int width : widths) {
                int elementWidth = width * MODULE_WIDTH;
                if (bar) {
                    svg.writeEmptyElement("rect");
                    svg.writeAttribute("x", Integer.toString(x));
                    svg.writeAttribute("y", "0");
                    svg.writeAttribute("width", Integer.toString(elementWidth));
                    svg.writeAttribute("height", Integer.toString(BAR_HEIGHT));
                    svg.writeAttribute("fill", BAR_COLOUR);
                }
                x += elementWidth;
                bar = !bar;
            }

            // The human-readable line matters: when a scanner fails somebody has to read the number
            // and type it, and a barcode with no printed value makes that impossible.
            svg.writeStartElement("text");
            svg.writeAttribute("x", Integer.toString(svgWidth / 2));
            svg.writeAttribute("y", Integer.toString(BAR_HEIGHT + TEXT_BASELINE_OFFSET));
            svg.writeAttribute("text-anchor", "middle");
            svg.writeAttribute("font-family", "monospace");
            svg.writeAttribute("font-size", Integer.toString(FONT_SIZE));
            svg.writeAttribute("fill", BAR_COLOUR);
            svg.writeCharacters(caption(accessionNo, specimenType));
            svg.writeEndElement();

            svg.writeEndElement();
            svg.writeEndDocument();
            svg.flush();
            svg.close();
        } catch (XMLStreamException ex) {
            // Nothing here reads or parses external input, so this cannot fail on data. Wrapped
            // rather than swallowed: a label that silently came back empty would be printed blank.
            throw new IllegalStateException("Could not render the label for " + accessionNo, ex);
        }
        return out.toString();
    }

    private static String caption(String accessionNo, String specimenType) {
        if (specimenType == null || specimenType.isBlank()) {
            return accessionNo;
        }
        return accessionNo + "  " + specimenType.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
