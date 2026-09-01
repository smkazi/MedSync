package com.hms.laboratory.report;

/**
 * Page geometry and the identity printed on a report.
 *
 * <p>The identity fields are deployment configuration with deliberately blank defaults. The source
 * project hard-coded one laboratory's name, address and staff as fallbacks; nothing of the sort
 * belongs in this repository, and a report that silently prints somebody else's letterhead because
 * nobody configured it is worse than one that prints none. Blank fields simply do not render.
 */
public record ReportLayout(
        String labName,
        String labAddress,
        String labCity,
        String pathologistName,
        String technicianName,
        String footerNote) {

    /** A4 in points, which is what PDFBox measures in. */
    public static final float PAGE_WIDTH = 595f;
    public static final float PAGE_HEIGHT = 842f;
    public static final float MARGIN = 36f;

    public static final float TITLE_SIZE = 11f;
    public static final float HEADING_SIZE = 8.5f;
    public static final float BODY_SIZE = 8f;
    public static final float SMALL_SIZE = 6.5f;

    public static final float ROW_HEIGHT = 13f;

    /**
     * Column x-offsets from the left margin: test, result, unit, reference, flag.
     *
     * <p>Private, with {@link #columnX(int)} to read it. A {@code public static final} array is final
     * only in its reference - the elements stay writable by anything on the classpath, which for a
     * table of print positions means one careless line silently reformats every report. SpotBugs
     * flagged it as MS_PKGPROTECT and was right.
     */
    private static final float[] COLUMNS = {0f, 170f, 250f, 320f, 450f};

    /** The x-offset of column {@code index}, from the left margin. */
    public static float columnX(int index) {
        return COLUMNS[index];
    }

    public static int columnCount() {
        return COLUMNS.length;
    }

    public boolean hasLabIdentity() {
        return notBlank(labName) || notBlank(labAddress) || notBlank(labCity);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
