package com.hms.imaging.dicom;

/**
 * The handful of DICOM tags this platform actually reads.
 *
 * <p>DICOM defines several thousand. A registry of all of them would be a data file nobody
 * maintains and a parser that pretended to understand every one of them; what an imaging record
 * needs is identity — which patient, which study, which series, which instance — and enough about
 * the acquisition to describe it on a worklist. Everything else stays in the file, where the viewer
 * that renders it will read it.
 *
 * <p>A tag is a group and an element, each 16 bits, conventionally written {@code (0010,0020)}.
 * They are held here as a single int so a lookup is one comparison rather than two.
 */
public final class DicomTag {

    private DicomTag() {
    }

    public static int of(int group, int element) {
        return (group << 16) | element;
    }

    /** {@code (0002,0000)} — the length of the file meta group, which bounds the header. */
    public static final int FILE_META_GROUP_LENGTH = of(0x0002, 0x0000);

    /** {@code (0002,0010)} — how the dataset after the header is encoded. */
    public static final int TRANSFER_SYNTAX_UID = of(0x0002, 0x0010);

    /** {@code (0002,0002)} and {@code (0002,0003)} — what kind of object, and its unique id. */
    public static final int MEDIA_STORAGE_SOP_CLASS_UID = of(0x0002, 0x0002);
    public static final int MEDIA_STORAGE_SOP_INSTANCE_UID = of(0x0002, 0x0003);

    // ---- identity --------------------------------------------------------------

    public static final int SOP_CLASS_UID = of(0x0008, 0x0016);
    public static final int SOP_INSTANCE_UID = of(0x0008, 0x0018);
    public static final int STUDY_INSTANCE_UID = of(0x0020, 0x000D);
    public static final int SERIES_INSTANCE_UID = of(0x0020, 0x000E);
    public static final int STUDY_ID = of(0x0020, 0x0010);
    public static final int SERIES_NUMBER = of(0x0020, 0x0011);
    public static final int INSTANCE_NUMBER = of(0x0020, 0x0013);

    /**
     * {@code (0008,0050)} — the accession number.
     *
     * <p>The single most important tag here. It is what the modality copied off the worklist, and
     * therefore the only reliable link between the images that came back and the order that asked
     * for them. The laboratory matches a tube to an order by exactly this kind of number, for
     * exactly this reason.
     */
    public static final int ACCESSION_NUMBER = of(0x0008, 0x0050);

    // ---- the patient -----------------------------------------------------------

    public static final int PATIENT_NAME = of(0x0010, 0x0010);
    public static final int PATIENT_ID = of(0x0010, 0x0020);
    public static final int PATIENT_BIRTH_DATE = of(0x0010, 0x0030);
    public static final int PATIENT_SEX = of(0x0010, 0x0040);

    // ---- the acquisition -------------------------------------------------------

    public static final int MODALITY = of(0x0008, 0x0060);
    public static final int STUDY_DATE = of(0x0008, 0x0020);
    public static final int STUDY_TIME = of(0x0008, 0x0030);
    public static final int STUDY_DESCRIPTION = of(0x0008, 0x1030);
    public static final int SERIES_DESCRIPTION = of(0x0008, 0x103E);
    public static final int BODY_PART_EXAMINED = of(0x0018, 0x0015);
    public static final int INSTITUTION_NAME = of(0x0008, 0x0080);
    public static final int MANUFACTURER = of(0x0008, 0x0070);
    public static final int STATION_NAME = of(0x0008, 0x1010);
    public static final int REFERRING_PHYSICIAN_NAME = of(0x0008, 0x0090);

    // ---- the pixels, which this service records the shape of and does not decode ----

    public static final int ROWS = of(0x0028, 0x0010);
    public static final int COLUMNS = of(0x0028, 0x0011);
    public static final int NUMBER_OF_FRAMES = of(0x0028, 0x0008);
    public static final int BITS_ALLOCATED = of(0x0028, 0x0100);

    /**
     * {@code (7FE0,0010)} — the pixel data.
     *
     * <p>Known so the parser can stop when it reaches it. Reading a 500-megabyte element into
     * memory to discover it is the picture would make parsing a study a memory event, and nothing
     * this service does needs the pixels: it records what exists and what it is of, and a viewer
     * reads the file itself.
     */
    public static final int PIXEL_DATA = of(0x7FE0, 0x0010);

    /** A tag as it is written in the standard and in every error message about one. */
    public static String format(int tag) {
        return "(%04X,%04X)".formatted(tag >>> 16, tag & 0xFFFF);
    }
}
