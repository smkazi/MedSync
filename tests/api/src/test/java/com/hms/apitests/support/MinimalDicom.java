package com.hms.apitests.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The smallest DICOM Part 10 file that can be filed against a request.
 *
 * <p><strong>Why this is not {@code DicomWriter}.</strong> imaging-service's test sources have a
 * fuller encoder, and copying code is normally the wrong answer — but this module depends on
 * nothing from the platform on purpose. That independence is what makes it a black-box suite: it
 * talks HTTP to a deployed gateway and holds no service's classes, no entities and no application
 * context, so a green run here means the deployment works rather than that a classpath does. Taking
 * a test-jar dependency on a service to borrow sixty lines would trade that away.
 *
 * <p>And this is not a second implementation of the difficult parts. {@code DicomWriter} exists to
 * exercise the parser: implicit VR, both byte orders, undefined-length sequences, a transfer syntax
 * the file switches to halfway through itself. None of that is here. This writes explicit VR little
 * endian and nothing else, because what the journey needs to prove is that a file carrying an
 * accession number reaches the right request through the gateway — the parser's own edge cases are
 * covered by twenty-eight tests one module along, where they belong.
 *
 * <p>Bytes rather than a committed fixture, for the reason the other writer also gives: a real
 * radiograph is fifteen megabytes of pixels around two hundred bytes of identity, and nobody can
 * review one in a diff. The accession number has to be minted per run anyway, so the file has to be
 * built per run.
 */
public final class MinimalDicom {

    private static final String EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";
    private static final String CR_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.1";

    // (group, element) of everything written below. Named rather than inlined, because a bare
    // 0x00080050 in the middle of a byte stream is unreadable and one digit out is a different
    // field entirely.
    private static final int SOP_CLASS_UID = tag(0x0002, 0x0002);
    private static final int SOP_INSTANCE_UID = tag(0x0002, 0x0003);
    private static final int TRANSFER_SYNTAX_UID = tag(0x0002, 0x0010);
    private static final int META_GROUP_LENGTH = tag(0x0002, 0x0000);
    private static final int SOP_CLASS_UID_DATASET = tag(0x0008, 0x0016);
    private static final int SOP_INSTANCE_UID_DATASET = tag(0x0008, 0x0018);
    private static final int ACCESSION_NUMBER = tag(0x0008, 0x0050);
    private static final int MODALITY = tag(0x0008, 0x0060);
    private static final int INSTITUTION_NAME = tag(0x0008, 0x0080);
    private static final int STUDY_DESCRIPTION = tag(0x0008, 0x1030);
    private static final int SERIES_DESCRIPTION = tag(0x0008, 0x103E);
    private static final int PATIENT_NAME = tag(0x0010, 0x0010);
    private static final int PATIENT_ID = tag(0x0010, 0x0020);
    private static final int BODY_PART = tag(0x0018, 0x0015);
    private static final int STUDY_INSTANCE_UID = tag(0x0020, 0x000D);
    private static final int SERIES_INSTANCE_UID = tag(0x0020, 0x000E);
    private static final int SERIES_NUMBER = tag(0x0020, 0x0011);
    private static final int PIXEL_DATA = tag(0x7FE0, 0x0010);

    private MinimalDicom() {
    }

    /**
     * One instance of one series of one study, carrying {@code accession}.
     *
     * <p>{@code patientId} is deliberately a parameter and deliberately wrong in the tests that
     * matter: the header's patient identifiers are whatever was typed at a modality console, and
     * proving the platform does not believe them is half of what this fixture is for.
     */
    public static byte[] instance(String accession, String patientId, String studyUid,
                                  String seriesUid, String sopUid) {
        // In ascending tag order, which the standard requires of a dataset and which a parser is
        // entitled to rely on to stop early.
        //
        // (0008,0016) and (0008,0018) appear here as well as in the file meta group, because that
        // is where a real file carries them and where the platform reads them: the meta group
        // describes the file and the dataset describes the image. Leaving them out of the dataset
        // is the mistake that produced this comment — the ingest answered 400 naming the missing
        // SOP instance UID, correctly, about a fixture that had one in the wrong place.
        ByteArrayOutputStream dataset = new ByteArrayOutputStream();
        str(dataset, SOP_CLASS_UID_DATASET, "UI", CR_IMAGE_STORAGE);
        str(dataset, SOP_INSTANCE_UID_DATASET, "UI", sopUid);
        str(dataset, ACCESSION_NUMBER, "SH", accession);
        str(dataset, MODALITY, "CS", "CR");
        str(dataset, INSTITUTION_NAME, "LO", "Automation");
        str(dataset, STUDY_DESCRIPTION, "LO", "Chest PA");
        str(dataset, SERIES_DESCRIPTION, "LO", "PA erect");
        str(dataset, PATIENT_NAME, "PN", "Typed^At^Console");
        str(dataset, PATIENT_ID, "LO", patientId);
        str(dataset, BODY_PART, "CS", "CHEST");
        str(dataset, STUDY_INSTANCE_UID, "UI", studyUid);
        str(dataset, SERIES_INSTANCE_UID, "UI", seriesUid);
        str(dataset, SERIES_NUMBER, "IS", "1");
        // Sixteen bytes of nothing, so the parser has the pixel-data element to stop at. A file
        // with no pixels at all is a structured report, which is a different thing.
        element(dataset, PIXEL_DATA, "OW", new byte[16]);

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        str(meta, SOP_CLASS_UID, "UI", CR_IMAGE_STORAGE);
        str(meta, SOP_INSTANCE_UID, "UI", sopUid);
        str(meta, TRANSFER_SYNTAX_UID, "UI", EXPLICIT_VR_LE);
        byte[] metaBytes = meta.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[128]);
        out.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        // (0002,0000) counts only what follows it, which is the whole reason it is written after
        // the group it measures has been assembled.
        element(out, META_GROUP_LENGTH, "UL", uint32(metaBytes.length));
        out.writeBytes(metaBytes);
        out.writeBytes(dataset.toByteArray());
        return out.toByteArray();
    }

    /** A UID under the DICOM-registered 2.25 branch, which takes any decimal integer. */
    public static String uid(String suffix) {
        return "2.25." + suffix;
    }

    private static void str(ByteArrayOutputStream out, int tag, String vr, String value) {
        byte[] body = value.getBytes(StandardCharsets.ISO_8859_1);
        if (body.length % 2 == 1) {
            byte[] padded = new byte[body.length + 1];
            System.arraycopy(body, 0, padded, 0, body.length);
            // A UID pads with a null and everything else with a space, per the standard.
            padded[body.length] = (byte) ("UI".equals(vr) ? 0x00 : 0x20);
            body = padded;
        }
        element(out, tag, vr, body);
    }

    private static void element(ByteArrayOutputStream out, int tag, String vr, byte[] body) {
        out.write(tag >>> 16);
        out.write(tag >>> 24);
        out.write(tag);
        out.write(tag >>> 8);
        out.writeBytes(vr.getBytes(StandardCharsets.US_ASCII));
        if ("OW".equals(vr)) {
            // OB, OW, OF, SQ, UT and UN carry two reserved bytes and a four-byte length; every
            // other VR carries a two-byte length. Getting this wrong shifts the whole rest of the
            // dataset, which is why the one long-form VR used here is spelled out.
            out.writeBytes(new byte[] {0, 0});
            out.writeBytes(uint32(body.length));
        } else {
            out.write(body.length);
            out.write(body.length >>> 8);
        }
        out.writeBytes(body);
    }

    private static byte[] uint32(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16),
                (byte) (value >>> 24)};
    }

    private static int tag(int group, int element) {
        return (group << 16) | element;
    }
}
