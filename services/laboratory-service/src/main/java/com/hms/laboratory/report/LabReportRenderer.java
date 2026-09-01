package com.hms.laboratory.report;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.hms.laboratory.web.dto.LabDtos;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Renders a pathology report as a PDF.
 *
 * <p>Modelled on the haemogram in smkazi/HaematologyIS: a header carrying the laboratory's identity,
 * the patient and sample block, results grouped into red cell / white cell / platelet sections with
 * their reference intervals and flags, the smear morphology, the interpretive comments, a QR code,
 * and a signature block.
 *
 * <p><strong>The QR carries the results as plain text, not a link.</strong> Copied deliberately from
 * the source, whose docstring is explicit about it, and it is the right call for a reason worth
 * writing down: the QR is printed on a sheet the reader is already holding, so it discloses nothing
 * the paper does not. It re-encodes what is in their hand into something their phone can read with no
 * portal, no login and no working internet connection. That is the opposite of an SMS, which sends
 * results to a stored phone number that may be stale or shared — which is why the notification path
 * will carry no results at all. Same data, different channel, different answer.
 *
 * <p><strong>An unverified report is watermarked PROVISIONAL.</strong> Results exist from the moment
 * the bench enters them, and a clinician will sometimes need to see them before the pathologist has
 * signed. Refusing to render would push people to screenshots; rendering something indistinguishable
 * from a signed report would be worse. So it renders, diagonally stamped, with no signature block.
 */
public final class LabReportRenderer {

    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneOffset.UTC);

    /** Point size of one QR module. Four keeps the code scannable off a laser print. */
    private static final int QR_MODULE_PIXELS = 4;
    private static final float QR_RENDER_SIZE = 96f;

    private final PDFont regular;
    private final PDFont bold;
    private final ReportLayout layout;

    public LabReportRenderer(ReportLayout layout) {
        this.layout = layout;
        this.regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    /**
     * Builds the document.
     *
     * @param groups sections in print order; each holds the rows that belong to it
     */
    public byte[] render(ReportContent content, List<ReportSection> groups) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(ReportLayout.PAGE_WIDTH, ReportLayout.PAGE_HEIGHT));
            document.addPage(page);

            Cursor cursor = new Cursor(ReportLayout.PAGE_HEIGHT - ReportLayout.MARGIN);
            try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                header(out, cursor);
                patientBlock(out, cursor, content);
                title(out, cursor, content);
                for (ReportSection section : groups) {
                    section(out, cursor, section);
                }
                morphology(out, cursor, content);
                notes(out, cursor, content);
                signatures(out, cursor, content);
                footer(out, content);
                if (!content.verified()) {
                    provisionalWatermark(out);
                }
            }
            qrCode(document, page, content);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            // Nothing here parses external input, so this is a programming or memory fault rather
            // than bad data. Wrapped rather than swallowed: a silently empty PDF would be printed.
            throw new UncheckedIOException("Could not render the report for " + content.accessionNo(), ex);
        }
    }

    // ---- sections --------------------------------------------------------------

    private void header(PDPageContentStream out, Cursor cursor) throws IOException {
        if (!layout.hasLabIdentity()) {
            return;
        }
        if (notBlank(layout.labName())) {
            text(out, bold, ReportLayout.TITLE_SIZE, ReportLayout.MARGIN, cursor.y, layout.labName());
            cursor.down(14f);
        }
        for (String line : new String[] {layout.labAddress(), layout.labCity()}) {
            if (notBlank(line)) {
                text(out, regular, ReportLayout.SMALL_SIZE, ReportLayout.MARGIN, cursor.y, line);
                cursor.down(9f);
            }
        }
        cursor.down(4f);
        rule(out, cursor.y);
        cursor.down(12f);
    }

    private void patientBlock(PDPageContentStream out, Cursor cursor, ReportContent content)
            throws IOException {
        float right = ReportLayout.MARGIN + 300f;
        text(out, bold, ReportLayout.BODY_SIZE, ReportLayout.MARGIN, cursor.y,
                "Name: " + content.patientName());
        text(out, regular, ReportLayout.BODY_SIZE, right, cursor.y,
                "Age / Sex: " + content.ageSex());
        cursor.down(11f);

        text(out, regular, ReportLayout.BODY_SIZE, ReportLayout.MARGIN, cursor.y,
                "MRN: " + content.patientMrn());
        text(out, regular, ReportLayout.BODY_SIZE, right, cursor.y,
                "Reported: " + REPORT_DATE.format(content.reportedAt()));
        cursor.down(11f);

        text(out, regular, ReportLayout.BODY_SIZE, ReportLayout.MARGIN, cursor.y,
                "Sample: " + content.accessionNo());
        text(out, regular, ReportLayout.BODY_SIZE, right, cursor.y,
                "Ordered by: " + content.orderedBy());
        cursor.down(14f);
    }

    private void title(PDPageContentStream out, Cursor cursor, ReportContent content)
            throws IOException {
        String heading = content.title();
        float width = stringWidth(bold, ReportLayout.TITLE_SIZE, heading);
        text(out, bold, ReportLayout.TITLE_SIZE,
                (ReportLayout.PAGE_WIDTH - width) / 2f, cursor.y, heading);
        cursor.down(6f);
        rule(out, cursor.y);
        cursor.down(14f);
    }

    private void section(PDPageContentStream out, Cursor cursor, ReportSection section)
            throws IOException {
        if (section.rows().isEmpty()) {
            return;
        }
        text(out, bold, ReportLayout.HEADING_SIZE, ReportLayout.MARGIN, cursor.y, section.title());
        cursor.down(12f);

        columnHeadings(out, cursor);
        for (ReportRow row : section.rows()) {
            float x = ReportLayout.MARGIN;
            text(out, regular, ReportLayout.BODY_SIZE, x + ReportLayout.columnX(0), cursor.y,
                    row.displayName());
            // The value is bold when flagged, so an abnormal number is findable at a glance rather
            // than needing the flag column to be read across.
            text(out, row.flagged() ? bold : regular, ReportLayout.BODY_SIZE,
                    x + ReportLayout.columnX(1), cursor.y, row.value());
            text(out, regular, ReportLayout.BODY_SIZE, x + ReportLayout.columnX(2), cursor.y,
                    row.unit());
            text(out, regular, ReportLayout.BODY_SIZE, x + ReportLayout.columnX(3), cursor.y,
                    row.referenceRange());
            text(out, row.flagged() ? bold : regular, ReportLayout.BODY_SIZE,
                    x + ReportLayout.columnX(4), cursor.y, row.flag());
            cursor.down(ReportLayout.ROW_HEIGHT);
        }
        cursor.down(6f);
    }

    private void columnHeadings(PDPageContentStream out, Cursor cursor) throws IOException {
        String[] headings = {"Test", "Result", "Unit", "Reference interval", "Flag"};
        for (int i = 0; i < headings.length; i++) {
            text(out, bold, ReportLayout.SMALL_SIZE,
                    ReportLayout.MARGIN + ReportLayout.columnX(i), cursor.y, headings[i]);
        }
        cursor.down(4f);
        rule(out, cursor.y);
        cursor.down(11f);
    }

    private void morphology(PDPageContentStream out, Cursor cursor, ReportContent content)
            throws IOException {
        LabDtos.MorphologyView morphology = content.morphology();
        if (morphology == null) {
            return;
        }
        text(out, bold, ReportLayout.HEADING_SIZE, ReportLayout.MARGIN, cursor.y,
                "Peripheral smear morphology");
        cursor.down(12f);

        if (notBlank(morphology.comment())) {
            wrapped(out, cursor, morphology.comment(), ReportLayout.BODY_SIZE);
        } else {
            labelled(out, cursor, "Red cells", morphology.redCells());
            labelled(out, cursor, "White cells", morphology.whiteCells());
            labelled(out, cursor, "Platelets", morphology.platelets());
        }
        if (morphology.derived()) {
            // Said on the page, not just in the API. A reader is entitled to know which sentences
            // on a report a person wrote.
            cursor.down(2f);
            text(out, regular, ReportLayout.SMALL_SIZE, ReportLayout.MARGIN, cursor.y,
                    "Derived from the measured indices; not a reported smear examination.");
            cursor.down(10f);
        }
        cursor.down(6f);
    }

    private void notes(PDPageContentStream out, Cursor cursor, ReportContent content)
            throws IOException {
        if (content.notes().isEmpty()) {
            return;
        }
        text(out, bold, ReportLayout.HEADING_SIZE, ReportLayout.MARGIN, cursor.y, "Comments");
        cursor.down(12f);
        for (String note : content.notes()) {
            wrapped(out, cursor, "- " + note, ReportLayout.BODY_SIZE);
        }
        cursor.down(6f);
    }

    private void signatures(PDPageContentStream out, Cursor cursor, ReportContent content)
            throws IOException {
        if (!content.verified()) {
            // No signature block on an unsigned report. A name under a "Pathologist" caption is a
            // claim that somebody reviewed it.
            return;
        }
        float y = Math.max(cursor.y - 24f, ReportLayout.MARGIN + 60f);
        float right = ReportLayout.PAGE_WIDTH - ReportLayout.MARGIN - 150f;

        if (notBlank(content.verifiedBy())) {
            text(out, bold, ReportLayout.BODY_SIZE, ReportLayout.MARGIN, y, content.verifiedBy());
            text(out, regular, ReportLayout.SMALL_SIZE, ReportLayout.MARGIN, y - 9f,
                    "Verified and released");
        }
        if (notBlank(layout.pathologistName())) {
            text(out, bold, ReportLayout.BODY_SIZE, right, y, layout.pathologistName());
            text(out, regular, ReportLayout.SMALL_SIZE, right, y - 9f, "Pathologist");
        }
        cursor.moveTo(y - 24f);
    }

    private void footer(PDPageContentStream out, ReportContent content) throws IOException {
        float y = ReportLayout.MARGIN + 10f;
        rule(out, y + 10f);
        String note = notBlank(layout.footerNote()) ? layout.footerNote()
                : "Generated by a laboratory information system. Please correlate clinically.";
        text(out, regular, ReportLayout.SMALL_SIZE, ReportLayout.MARGIN, y, note);
        String reference = "Report " + content.accessionNo();
        text(out, regular, ReportLayout.SMALL_SIZE,
                ReportLayout.PAGE_WIDTH - ReportLayout.MARGIN
                        - stringWidth(regular, ReportLayout.SMALL_SIZE, reference), y, reference);
    }

    /**
     * A diagonal PROVISIONAL stamp across an unverified report.
     *
     * <p>Grey and behind nothing — drawn last, over the content, because a watermark under the text
     * is one photocopy away from invisible.
     */
    private void provisionalWatermark(PDPageContentStream out) throws IOException {
        out.saveGraphicsState();
        out.setNonStrokingColor(0.85f, 0.85f, 0.85f);
        out.beginText();
        out.setFont(bold, 54f);
        out.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(
                Math.toRadians(38), 100f, 260f));
        out.showText("PROVISIONAL");
        out.endText();
        out.restoreGraphicsState();
    }

    private void qrCode(PDDocument document, PDPage page, ReportContent content) throws IOException {
        BufferedImage image = qrImage(content.qrText());
        if (image == null) {
            return;
        }
        PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream out = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            float x = ReportLayout.PAGE_WIDTH - ReportLayout.MARGIN - QR_RENDER_SIZE;
            float y = ReportLayout.MARGIN + 30f;
            out.drawImage(xObject, x, y, QR_RENDER_SIZE, QR_RENDER_SIZE);
            text(out, regular, ReportLayout.SMALL_SIZE, x, y - 8f, "Scan for the result summary");
        }
    }

    private static BufferedImage qrImage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // Low correction keeps the symbol less dense, which matters because it carries a whole
        // result summary rather than a short URL. The source made the same trade.
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE,
                    0, 0, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            BufferedImage image = new BufferedImage(width * QR_MODULE_PIXELS,
                    height * QR_MODULE_PIXELS, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(java.awt.Color.BLACK);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (matrix.get(x, y)) {
                        graphics.fillRect(x * QR_MODULE_PIXELS, y * QR_MODULE_PIXELS,
                                QR_MODULE_PIXELS, QR_MODULE_PIXELS);
                    }
                }
            }
            graphics.dispose();
            return image;
        } catch (WriterException ex) {
            // A summary too long for any QR version. The report is still worth printing without it,
            // so this degrades rather than failing - the paper carries the same values anyway.
            return null;
        }
    }

    // ---- drawing helpers -------------------------------------------------------

    private void labelled(PDPageContentStream out, Cursor cursor, String label, String value)
            throws IOException {
        if (!notBlank(value)) {
            return;
        }
        text(out, regular, ReportLayout.BODY_SIZE, ReportLayout.MARGIN, cursor.y, label);
        text(out, regular, ReportLayout.BODY_SIZE, ReportLayout.MARGIN + 80f, cursor.y, value);
        cursor.down(11f);
    }

    /** Wraps on width, because an interpretive comment is a sentence and will not fit on one line. */
    private void wrapped(PDPageContentStream out, Cursor cursor, String value, float size)
            throws IOException {
        float available = ReportLayout.PAGE_WIDTH - 2 * ReportLayout.MARGIN;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (stringWidth(regular, size, candidate) > available && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        for (String rendered : lines) {
            text(out, regular, size, ReportLayout.MARGIN, cursor.y, rendered);
            cursor.down(size + 3f);
        }
    }

    private void text(PDPageContentStream out, PDFont font, float size, float x, float y,
                      String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(x, y);
        out.showText(sanitise(value));
        out.endText();
    }

    private void rule(PDPageContentStream out, float y) throws IOException {
        out.setStrokingColor(0.7f, 0.7f, 0.7f);
        out.setLineWidth(0.5f);
        out.moveTo(ReportLayout.MARGIN, y);
        out.lineTo(ReportLayout.PAGE_WIDTH - ReportLayout.MARGIN, y);
        out.stroke();
    }

    /**
     * Replaces characters the Standard 14 fonts cannot encode.
     *
     * <p>Helvetica here is WinAnsi, and {@code showText} throws on anything outside it. The
     * interpretive messages are full of en dashes and a degree sign, and one unencodable character
     * would otherwise abort the whole report — so they are folded to ASCII equivalents rather than
     * allowed to take the document down.
     */
    private static String sanitise(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '—', '–' -> out.append('-');
                case '‘', '’' -> out.append('\'');
                case '“', '”' -> out.append('"');
                case '×' -> out.append('x');
                case 'µ' -> out.append('u');
                case '°' -> out.append(" deg ");
                case '\n', '\r', '\t' -> out.append(' ');
                default -> out.append(c <= 0xFF ? c : '?');
            }
        }
        return out.toString();
    }

    private float stringWidth(PDFont font, float size, String value) {
        try {
            return font.getStringWidth(sanitise(value)) / 1000f * size;
        } catch (IOException ex) {
            return value.length() * size * 0.5f;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Tracks the vertical position as sections are written down the page. */
    private static final class Cursor {
        private float y;

        Cursor(float y) {
            this.y = y;
        }

        void down(float amount) {
            this.y -= amount;
        }

        void moveTo(float value) {
            this.y = value;
        }
    }
}
