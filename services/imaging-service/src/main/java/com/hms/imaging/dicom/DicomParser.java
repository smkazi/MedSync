package com.hms.imaging.dicom;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the header of a DICOM Part 10 file: enough to know what the image is and whose it is.
 *
 * <p>Dependency-free, in the spirit of the ASTM and HL7 parsers already here, and bounded on
 * purpose. It reads identity and description and <strong>stops at the pixel data</strong>. Nothing
 * this service does needs the pixels — it records what exists, what it is of, and which order it
 * answers — and reading a five-hundred-megabyte element into memory to discover it is the picture
 * would turn registering a study into a memory event.
 *
 * <p>Three things about the format matter and each is a way a naive reader gets it wrong.
 *
 * <p><strong>The file says how it is encoded, halfway through itself.</strong> A Part 10 file opens
 * with a 128-byte preamble, the four bytes {@code DICM}, and a file meta group that is
 * <em>always</em> explicit VR little endian. That group's {@code (0002,0010)} then names the
 * transfer syntax of everything after it, which may be implicit VR, explicit VR little endian, or
 * explicit VR big endian. So the parser switches encoding mid-file, at a boundary the file itself
 * declares.
 *
 * <p><strong>Implicit VR has no types in it.</strong> In implicit VR every element is a tag and a
 * four-byte length, and what kind of value it holds is knowable only from a dictionary. This parser
 * has a small one — the tags in {@link DicomTag} — and treats anything else as bytes it does not
 * need, which is the honest position for a reader that only wants identity.
 *
 * <p><strong>Some lengths are unknown.</strong> Sequences and encapsulated pixel data can be
 * written with length {@code 0xFFFFFFFF}, meaning "read until a delimiter". Rather than implement
 * sequence traversal for tags it never reads, the parser skips a sequence of unknown length by
 * scanning for its delimiter, and stops entirely at pixel data.
 */
public final class DicomParser {

    /** Where a Part 10 file's magic sits: after a 128-byte preamble. */
    private static final int PREAMBLE_LENGTH = 128;
    private static final String MAGIC = "DICM";

    private static final String IMPLICIT_VR_LITTLE_ENDIAN = "1.2.840.10008.1.2";
    private static final String EXPLICIT_VR_LITTLE_ENDIAN = "1.2.840.10008.1.2.1";
    private static final String EXPLICIT_VR_BIG_ENDIAN = "1.2.840.10008.1.2.2";

    /** Length written when a sequence or encapsulated pixel data runs to a delimiter instead. */
    private static final long UNDEFINED_LENGTH = 0xFFFFFFFFL;

    private static final int ITEM = DicomTag.of(0xFFFE, 0xE000);
    private static final int ITEM_DELIMITER = DicomTag.of(0xFFFE, 0xE00D);
    private static final int SEQUENCE_DELIMITER = DicomTag.of(0xFFFE, 0xE0DD);

    private DicomParser() {
    }

    /** Thrown for bytes that are not a DICOM file, which the caller turns into a refusal. */
    public static class DicomParseException extends RuntimeException {

        public DicomParseException(String message) {
            super(message);
        }
    }

    /**
     * The header, as a map of tag to value with the accessors a caller actually wants.
     *
     * <p>Values are strings because every tag read here is one: a UID, a name, a date written
     * {@code YYYYMMDD}, a modality code. The numeric ones — rows, columns, frames — are read from
     * their binary representation and rendered as decimal, so a caller gets "512" whether the file
     * wrote it as text or as a 16-bit integer.
     */
    public record DicomHeader(Map<Integer, String> values, String transferSyntaxUid) {

        public DicomHeader {
            values = Map.copyOf(values);
        }

        public Optional<String> get(int tag) {
            return Optional.ofNullable(values.get(tag)).filter(value -> !value.isBlank());
        }

        public String getOrEmpty(int tag) {
            return get(tag).orElse("");
        }

        public Optional<Integer> getInt(int tag) {
            return get(tag).flatMap(value -> {
                try {
                    return Optional.of(Integer.valueOf(value.trim()));
                } catch (NumberFormatException ex) {
                    return Optional.empty();
                }
            });
        }
    }

    /**
     * Parses the header out of a Part 10 file.
     *
     * @throws DicomParseException when the bytes are not one. Refused rather than guessed at: a
     *         JPEG uploaded to a DICOM endpoint is a mistake worth reporting, and a parser that
     *         squinted at it would register a study with no identity in it.
     */
    public static DicomHeader parse(byte[] bytes) {
        if (bytes == null || bytes.length < PREAMBLE_LENGTH + 4) {
            throw new DicomParseException("Too short to be a DICOM file");
        }
        String magic = new String(bytes, PREAMBLE_LENGTH, 4, StandardCharsets.US_ASCII);
        if (!MAGIC.equals(magic)) {
            // Named, because the usual cause is somebody uploading the wrong file and the usual
            // fix is obvious the moment they are told which one.
            throw new DicomParseException(
                    "Not a DICOM file: expected 'DICM' after the 128-byte preamble, found '%s'"
                            .formatted(printable(magic)));
        }

        Map<Integer, String> values = new LinkedHashMap<>();
        Cursor cursor = new Cursor(bytes, PREAMBLE_LENGTH + 4);

        // The file meta group is always explicit VR little endian, whatever the dataset is.
        String transferSyntax = readGroup(cursor, values, true, false, true);

        boolean explicitVr;
        boolean bigEndian;
        switch (transferSyntax == null ? EXPLICIT_VR_LITTLE_ENDIAN : transferSyntax.trim()) {
            case IMPLICIT_VR_LITTLE_ENDIAN -> {
                explicitVr = false;
                bigEndian = false;
            }
            case EXPLICIT_VR_BIG_ENDIAN -> {
                explicitVr = true;
                bigEndian = true;
            }
            // Every compressed syntax — JPEG, JPEG 2000, RLE — encodes its *dataset* as explicit VR
            // little endian and compresses only the pixel data, which this parser stops at. So the
            // default is right for all of them, and a syntax nobody here has heard of is far more
            // likely to be one of those than a different dataset encoding.
            default -> {
                explicitVr = true;
                bigEndian = false;
            }
        }

        readGroup(cursor, values, explicitVr, bigEndian, false);
        return new DicomHeader(values, transferSyntax == null ? "" : transferSyntax.trim());
    }

    /**
     * Reads elements until the group ends, the file ends, or the pixels start.
     *
     * @param metaGroup when true, stops at the first element outside group {@code 0002} — the file
     *                  meta group ends where the dataset begins, and the two are encoded
     *                  differently, so reading past that boundary would decode the dataset with the
     *                  wrong rules
     * @return the transfer syntax, when this pass found one
     */
    private static String readGroup(Cursor cursor, Map<Integer, String> values, boolean explicitVr,
                                    boolean bigEndian, boolean metaGroup) {
        String transferSyntax = null;
        while (cursor.remaining() >= 8) {
            int mark = cursor.position();
            int group = cursor.uint16(bigEndian);
            int element = cursor.uint16(bigEndian);
            int tag = DicomTag.of(group, element);

            if (metaGroup && group != 0x0002) {
                // The dataset starts here and is encoded by different rules. Rewind so the caller
                // reads this element itself.
                cursor.seek(mark);
                return transferSyntax;
            }

            String vr = null;
            long length;
            if (explicitVr && group != 0xFFFE) {
                vr = cursor.ascii(2);
                if (isLongFormVr(vr)) {
                    cursor.skip(2);
                    length = cursor.uint32(bigEndian);
                } else {
                    length = cursor.uint16(bigEndian);
                }
            } else {
                // Implicit VR, and the item/delimiter tags which never carry one.
                length = cursor.uint32(bigEndian);
            }

            if (tag == DicomTag.PIXEL_DATA) {
                // Everything worth reading is before this. Stopping is the point.
                return transferSyntax;
            }
            if (tag == ITEM || tag == ITEM_DELIMITER || tag == SEQUENCE_DELIMITER) {
                // Item boundaries carry no value of their own; step over the header and continue,
                // which walks into a sequence's contents rather than around them. Harmless here,
                // because the tags this parser wants are not inside sequences and anything else is
                // ignored by tag.
                continue;
            }
            if (length == UNDEFINED_LENGTH) {
                // A sequence of unknown length. Rather than traverse it for tags never read, scan
                // to its delimiter.
                skipToSequenceDelimiter(cursor, bigEndian);
                continue;
            }
            if (length < 0 || length > cursor.remaining()) {
                // A length that runs past the end of the file. Stop rather than throw: everything
                // read so far is still true, and a truncated file is far more often a transfer that
                // was cut short than a file with nothing in it.
                return transferSyntax;
            }

            int size = (int) length;
            if (tag == DicomTag.TRANSFER_SYNTAX_UID) {
                transferSyntax = trimUid(cursor.ascii(size));
                values.put(tag, transferSyntax);
                continue;
            }
            if (WANTED.contains(tag)) {
                values.put(tag, readValue(cursor, tag, vr, size, bigEndian));
            } else {
                cursor.skip(size);
            }
        }
        return transferSyntax;
    }

    /** The tags worth keeping. Everything else is stepped over without being decoded. */
    private static final java.util.Set<Integer> WANTED = java.util.Set.of(
            DicomTag.MEDIA_STORAGE_SOP_CLASS_UID, DicomTag.MEDIA_STORAGE_SOP_INSTANCE_UID,
            DicomTag.SOP_CLASS_UID, DicomTag.SOP_INSTANCE_UID,
            DicomTag.STUDY_INSTANCE_UID, DicomTag.SERIES_INSTANCE_UID,
            DicomTag.STUDY_ID, DicomTag.SERIES_NUMBER, DicomTag.INSTANCE_NUMBER,
            DicomTag.ACCESSION_NUMBER,
            DicomTag.PATIENT_NAME, DicomTag.PATIENT_ID, DicomTag.PATIENT_BIRTH_DATE,
            DicomTag.PATIENT_SEX,
            DicomTag.MODALITY, DicomTag.STUDY_DATE, DicomTag.STUDY_TIME,
            DicomTag.STUDY_DESCRIPTION, DicomTag.SERIES_DESCRIPTION,
            DicomTag.BODY_PART_EXAMINED, DicomTag.INSTITUTION_NAME, DicomTag.MANUFACTURER,
            DicomTag.STATION_NAME, DicomTag.REFERRING_PHYSICIAN_NAME,
            DicomTag.ROWS, DicomTag.COLUMNS, DicomTag.NUMBER_OF_FRAMES,
            DicomTag.BITS_ALLOCATED);

    /**
     * The three tags this parser reads whose value is a binary integer rather than text.
     *
     * <p>Small and explicit, because in implicit VR there is no VR in the file to consult and the
     * decision has to come from somewhere. Guessing from the length does not work and the test
     * proves it: a modality of {@code "US"} is two bytes, and read as an unsigned short it is the
     * number 21333.
     *
     * <p>Note which tags are <em>not</em> here. {@code NumberOfFrames}, {@code SeriesNumber} and
     * {@code InstanceNumber} look numeric and are written as text — the standard's IS value
     * representation is a decimal string — so reading them as integers would be the same bug in the
     * other direction.
     */
    private static final java.util.Set<Integer> BINARY_INTEGERS = java.util.Set.of(
            DicomTag.ROWS, DicomTag.COLUMNS, DicomTag.BITS_ALLOCATED);

    /**
     * Reads one value as a string.
     *
     * <p>Decided by the VR when the file carries one, and by the tag when it does not. Both paths
     * end in a decimal string, so a caller gets "512" whether the file wrote the number in binary
     * or in text.
     */
    private static String readValue(Cursor cursor, int tag, String vr, int size, boolean bigEndian) {
        boolean binary = "US".equals(vr) || "UL".equals(vr)
                || (vr == null && BINARY_INTEGERS.contains(tag));
        if (binary && size == 2) {
            return String.valueOf(cursor.uint16(bigEndian));
        }
        if (binary && size == 4) {
            return String.valueOf(cursor.uint32(bigEndian));
        }
        String text = cursor.ascii(size);
        // DICOM pads a value to an even length with a space or a null, and neither is content.
        return text.replace('\0', ' ').trim();
    }

    /** Walks forward to the delimiter that closes a sequence of unknown length. */
    private static void skipToSequenceDelimiter(Cursor cursor, boolean bigEndian) {
        while (cursor.remaining() >= 8) {
            int group = cursor.uint16(bigEndian);
            int element = cursor.uint16(bigEndian);
            long length = cursor.uint32(bigEndian);
            if (DicomTag.of(group, element) == SEQUENCE_DELIMITER) {
                return;
            }
            if (length != UNDEFINED_LENGTH && length >= 0 && length <= cursor.remaining()) {
                cursor.skip((int) length);
            }
        }
    }

    /**
     * VRs whose length is a 32-bit number after two reserved bytes.
     *
     * <p>The rest write a 16-bit length immediately after the VR. Getting this list wrong
     * misreads every element after the first one of these, which is why it is a list and not a
     * guess.
     */
    private static boolean isLongFormVr(String vr) {
        return switch (vr) {
            case "OB", "OW", "OF", "OD", "OL", "OV", "SQ", "UT", "OT", "OP", "OTHER", "UN", "OTX" -> true;
            default -> false;
        };
    }

    /** A UID is padded with a trailing null to an even length; it is not part of the identifier. */
    private static String trimUid(String value) {
        return value.replace("\0", "").trim();
    }

    private static String printable(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(c >= 0x20 && c < 0x7F ? c : '.');
        }
        return out.toString();
    }

    /** A position in the byte array, with the two endiannesses DICOM allows. */
    private static final class Cursor {

        private final byte[] bytes;
        private int position;

        Cursor(byte[] bytes, int position) {
            this.bytes = bytes;
            this.position = position;
        }

        int position() {
            return position;
        }

        void seek(int to) {
            position = to;
        }

        int remaining() {
            return bytes.length - position;
        }

        void skip(int count) {
            position = Math.min(bytes.length, position + count);
        }

        int uint16(boolean bigEndian) {
            int a = bytes[position++] & 0xFF;
            int b = bytes[position++] & 0xFF;
            return bigEndian ? (a << 8) | b : (b << 8) | a;
        }

        long uint32(boolean bigEndian) {
            long a = bytes[position++] & 0xFFL;
            long b = bytes[position++] & 0xFFL;
            long c = bytes[position++] & 0xFFL;
            long d = bytes[position++] & 0xFFL;
            return bigEndian
                    ? (a << 24) | (b << 16) | (c << 8) | d
                    : (d << 24) | (c << 16) | (b << 8) | a;
        }

        String ascii(int length) {
            int take = Math.min(length, remaining());
            String value = new String(bytes, position, take, StandardCharsets.ISO_8859_1);
            position += take;
            return value;
        }
    }
}
