package com.hms.common.barcode;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * A Code 128 symbol, sized and drawn.
 *
 * <p>This class exists because two different labels now carry a barcode — a specimen tube and a
 * patient's wristband — and the numbers that decide whether a symbol scans are not obvious enough
 * to be worth typing twice. Module width, bar height, the quiet zone and the white ground are all
 * scanning decisions with reasons attached, and a fix applied to one label and not the other is the
 * kind of defect that shows up as "the handheld works on the ward but not in the lab".
 *
 * <p>What is <em>not</em> shared is the label itself. A tube label carries an accession number and
 * deliberately no identity; a wristband carries the patient's name because identity is the entire
 * point of it. Those are opposite rules and they belong to the services that own the data, so this
 * class draws a symbol into a document somebody else is writing rather than producing a document of
 * its own.
 *
 * <p>Drawing happens through the caller's {@link XMLStreamWriter} for the reason
 * {@code SpecimenLabelRenderer} records: an earlier version of that renderer appended strings with
 * its own escaping helper, and FindSecBugs was right to flag it. A writer escapes as a property of
 * the API rather than as something every future caller has to remember.
 */
public final class BarcodeSvg {

    /**
     * Printed width of one narrow element. Two units keeps the symbol readable by cheap scanners.
     */
    public static final int MODULE_WIDTH = 2;

    /**
     * Quiet zone, in modules.
     *
     * <p>Ten, the specification's minimum. Scanners need blank space to find the symbol's edges; a
     * label trimmed flush to the first bar reads intermittently, which is worse than one that never
     * reads at all because nobody investigates it.
     */
    public static final int QUIET_ZONE_MODULES = 10;

    private static final String BAR_COLOUR = "#000000";

    private final int[] widths;
    private final int barHeight;

    private BarcodeSvg(int[] widths, int barHeight) {
        this.widths = widths;
        this.barHeight = barHeight;
    }

    /**
     * Encodes {@code value} and prepares to draw it {@code barHeight} units tall.
     *
     * @throws com.hms.common.error.BadRequestException if the value cannot be encoded — refused
     *                                                  rather than substituted, because a label that
     *                                                  scans as the wrong identifier is worse than
     *                                                  no label at all.
     */
    public static BarcodeSvg of(String value, int barHeight) {
        return new BarcodeSvg(Code128.encode(value), barHeight);
    }

    /** Total width including both quiet zones, so a caller can size the document around it. */
    public int width() {
        int modules = 0;
        for (int element : widths) {
            modules += element;
        }
        return (modules + 2 * QUIET_ZONE_MODULES) * MODULE_WIDTH;
    }

    /** Height of the bars alone; whatever the label puts under them is the caller's business. */
    public int height() {
        return barHeight;
    }

    /**
     * Draws the bars with their top-left corner at {@code (x, y)}, quiet zone included.
     *
     * <p>The quiet zone is left blank rather than painted: the caller is expected to have laid a
     * white ground under the whole label, because a transparent background printed onto anything but
     * white paper changes the contrast the scanner depends on.
     */
    public void writeInto(XMLStreamWriter svg, int x, int y) throws XMLStreamException {
        int at = x + QUIET_ZONE_MODULES * MODULE_WIDTH;
        boolean bar = true;
        for (int element : widths) {
            int elementWidth = element * MODULE_WIDTH;
            if (bar) {
                svg.writeEmptyElement("rect");
                svg.writeAttribute("x", Integer.toString(at));
                svg.writeAttribute("y", Integer.toString(y));
                svg.writeAttribute("width", Integer.toString(elementWidth));
                svg.writeAttribute("height", Integer.toString(barHeight));
                svg.writeAttribute("fill", BAR_COLOUR);
            }
            at += elementWidth;
            bar = !bar;
        }
    }
}
