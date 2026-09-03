package com.hms.imaging.dicom;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A test-only DICOM Part 10 encoder.
 *
 * <p>Extracted from {@code DicomParserTest}, where it started life, once the ingest tests needed to
 * build files too. Shared rather than copied: it is a hundred lines of bit-twiddling that has to
 * agree with the parser, and two copies would eventually stop agreeing with each other.
 *
 * <p>Not a general DICOM writer and not trying to be. It writes exactly the structures the tests
 * need — explicit and implicit VR, both byte orders, an undefined-length sequence, and pixel data
 * for the parser to stop at. Which is also why building bytes beats committing a fixture: a real
 * radiograph is fifteen megabytes of pixels around two hundred bytes of identity, and nobody can
 * review one in a diff.
 */
public final class DicomWriter {

    public static final String IMPLICIT_VR_LE = "1.2.840.10008.1.2";
    public static final String EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";
    public static final String EXPLICIT_VR_BE = "1.2.840.10008.1.2.2";
    /** JPEG baseline: a compressed syntax, whose <em>dataset</em> is still explicit VR little endian. */
    public static final String JPEG_BASELINE = "1.2.840.10008.1.2.4.50";

    private static final String DEFAULT_SOP_UID =
            "1.2.826.0.1.3680043.8.498.30303030303030303030";

    private final String sopUid;
    private final String transferSyntax;
    private final ByteArrayOutputStream dataset = new ByteArrayOutputStream();
    private final boolean explicitVr;
    private final boolean bigEndian;

    public DicomWriter(String transferSyntax) {
        this(transferSyntax, DEFAULT_SOP_UID);
    }

    /**
     * With an explicit SOP instance UID.
     *
     * <p>It goes into the file meta group as well as the dataset, because that is where a real file
     * carries it — and the ingest tests need two distinct instances of one series, which is exactly
     * the case a single hard-coded UID could not express.
     */
    public DicomWriter(String transferSyntax, String sopUid) {
        this.transferSyntax = transferSyntax;
        this.sopUid = sopUid;
        this.explicitVr = !IMPLICIT_VR_LE.equals(transferSyntax);
        this.bigEndian = EXPLICIT_VR_BE.equals(transferSyntax);
    }

    public DicomWriter str(int tag, String vr, String value) {
        byte[] body = value.getBytes(StandardCharsets.ISO_8859_1);
        // Padded to an even length, as the standard requires: with a null for a UID and a space
        // for everything else.
        if (body.length % 2 == 1) {
            byte[] padded = new byte[body.length + 1];
            System.arraycopy(body, 0, padded, 0, body.length);
            padded[body.length] = (byte) ("UI".equals(vr) ? 0x00 : 0x20);
            body = padded;
        }
        element(dataset, tag, vr, body, explicitVr, bigEndian);
        return this;
    }

    public DicomWriter uint16(int tag, int value) {
        byte[] body = bigEndian
                ? new byte[] {(byte) (value >> 8), (byte) value}
                : new byte[] {(byte) value, (byte) (value >> 8)};
        element(dataset, tag, "US", body, explicitVr, bigEndian);
        return this;
    }

    /** A sequence written with an undefined length and closed by its delimiter. */
    public DicomWriter undefinedLengthSequence(int tag) {
        writeTag(dataset, tag, bigEndian);
        if (explicitVr) {
            dataset.writeBytes("SQ".getBytes(StandardCharsets.US_ASCII));
            dataset.writeBytes(new byte[] {0, 0});
        }
        dataset.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        // The delimiter that closes it, with a zero length.
        writeTag(dataset, DicomTag.of(0xFFFE, 0xE0DD), bigEndian);
        dataset.writeBytes(new byte[] {0, 0, 0, 0});
        return this;
    }

    /** Pixel data, so the parser has something to stop at. */
    public DicomWriter pixels(int byteCount) {
        element(dataset, DicomTag.PIXEL_DATA, "OW", new byte[byteCount], explicitVr, bigEndian);
        return this;
    }

    public byte[] build() {
        // The file meta group: always explicit VR little endian, whatever the dataset is.
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        element(meta, DicomTag.MEDIA_STORAGE_SOP_CLASS_UID, "UI",
                padded("1.2.840.10008.5.1.4.1.1.1"), true, false);
        element(meta, DicomTag.MEDIA_STORAGE_SOP_INSTANCE_UID, "UI", padded(sopUid), true, false);
        element(meta, DicomTag.TRANSFER_SYNTAX_UID, "UI", padded(transferSyntax), true, false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[128]);
        out.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        // (0002,0000) is the group's own length, and it counts only what follows it.
        byte[] metaBytes = meta.toByteArray();
        element(out, DicomTag.FILE_META_GROUP_LENGTH, "UL",
                new byte[] {(byte) metaBytes.length, (byte) (metaBytes.length >> 8),
                        (byte) (metaBytes.length >> 16), (byte) (metaBytes.length >> 24)},
                true, false);
        out.writeBytes(metaBytes);
        out.writeBytes(dataset.toByteArray());
        return out.toByteArray();
    }

    private static byte[] padded(String uid) {
        byte[] body = uid.getBytes(StandardCharsets.US_ASCII);
        if (body.length % 2 == 0) {
            return body;
        }
        byte[] out = new byte[body.length + 1];
        System.arraycopy(body, 0, out, 0, body.length);
        return out;
    }

    private static void writeTag(ByteArrayOutputStream out, int tag, boolean bigEndian) {
        int group = tag >>> 16;
        int element = tag & 0xFFFF;
        if (bigEndian) {
            out.write(group >> 8);
            out.write(group);
            out.write(element >> 8);
            out.write(element);
        } else {
            out.write(group);
            out.write(group >> 8);
            out.write(element);
            out.write(element >> 8);
        }
    }

    private static void element(ByteArrayOutputStream out, int tag, String vr, byte[] body,
                                boolean explicitVr, boolean bigEndian) {
        writeTag(out, tag, bigEndian);
        boolean longForm = "OB".equals(vr) || "OW".equals(vr) || "SQ".equals(vr)
                || "UT".equals(vr) || "UN".equals(vr);
        if (explicitVr) {
            out.writeBytes(vr.getBytes(StandardCharsets.US_ASCII));
            if (longForm) {
                out.writeBytes(new byte[] {0, 0});
                writeUint32(out, body.length, bigEndian);
            } else {
                writeUint16(out, body.length, bigEndian);
            }
        } else {
            writeUint32(out, body.length, bigEndian);
        }
        out.writeBytes(body);
    }

    private static void writeUint16(ByteArrayOutputStream out, int value, boolean bigEndian) {
        if (bigEndian) {
            out.write(value >> 8);
            out.write(value);
        } else {
            out.write(value);
            out.write(value >> 8);
        }
    }

    private static void writeUint32(ByteArrayOutputStream out, int value, boolean bigEndian) {
        if (bigEndian) {
            out.write(value >> 24);
            out.write(value >> 16);
            out.write(value >> 8);
            out.write(value);
        } else {
            out.write(value);
            out.write(value >> 8);
            out.write(value >> 16);
            out.write(value >> 24);
        }
    }
}
