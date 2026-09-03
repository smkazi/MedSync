package com.hms.imaging.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DICOM header reader, against files this test builds byte by byte.
 *
 * <p>Constructed rather than fixtures, and that is the point: a real chest radiograph is fifteen
 * megabytes of pixels wrapped around two hundred bytes of identity, and committing one to prove the
 * parser reads a study UID would be a large binary nobody can review. Building the bytes means the
 * cases that matter can be built — implicit VR, big endian, a sequence of unknown length, a file cut
 * short mid-transfer — each of which is a real way a real file arrives and a real way a naive
 * reader gets it wrong.
 *
 * <p>{@link DicomWriter} is a test-only encoder of the same format. It is not a general DICOM
 * writer and does not try to be; it writes exactly the structures these cases need.
 */
class DicomParserTest {

    private static final String IMPLICIT_VR_LE = DicomWriter.IMPLICIT_VR_LE;
    private static final String EXPLICIT_VR_LE = DicomWriter.EXPLICIT_VR_LE;
    private static final String EXPLICIT_VR_BE = DicomWriter.EXPLICIT_VR_BE;
    private static final String JPEG_BASELINE = DicomWriter.JPEG_BASELINE;

    private static final String STUDY_UID = "1.2.826.0.1.3680043.8.498.10101010101010101010";
    private static final String SERIES_UID = "1.2.826.0.1.3680043.8.498.20202020202020202020";
    private static final String SOP_UID = "1.2.826.0.1.3680043.8.498.30303030303030303030";

    @Test
    @DisplayName("reads identity out of an ordinary explicit VR little endian file")
    void readsAnOrdinaryFile() {
        byte[] file = new DicomWriter(EXPLICIT_VR_LE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-2026-0001")
                .str(DicomTag.PATIENT_ID, "LO", "MRN-2026-000010")
                .str(DicomTag.PATIENT_NAME, "PN", "Noorani^Farida")
                .str(DicomTag.PATIENT_SEX, "CS", "F")
                .str(DicomTag.MODALITY, "CS", "CR")
                .str(DicomTag.STUDY_INSTANCE_UID, "UI", STUDY_UID)
                .str(DicomTag.SERIES_INSTANCE_UID, "UI", SERIES_UID)
                .str(DicomTag.SOP_INSTANCE_UID, "UI", SOP_UID)
                .str(DicomTag.STUDY_DESCRIPTION, "LO", "Chest PA")
                .str(DicomTag.BODY_PART_EXAMINED, "CS", "CHEST")
                .uint16(DicomTag.ROWS, 2048)
                .uint16(DicomTag.COLUMNS, 2500)
                .pixels(64)
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        // The accession number is the whole reason this parse is worth doing: it is what the
        // modality copied off the worklist, and the only reliable link back to the order.
        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-2026-0001");
        assertThat(header.getOrEmpty(DicomTag.STUDY_INSTANCE_UID)).isEqualTo(STUDY_UID);
        assertThat(header.getOrEmpty(DicomTag.SERIES_INSTANCE_UID)).isEqualTo(SERIES_UID);
        assertThat(header.getOrEmpty(DicomTag.SOP_INSTANCE_UID)).isEqualTo(SOP_UID);
        assertThat(header.getOrEmpty(DicomTag.PATIENT_ID)).isEqualTo("MRN-2026-000010");
        assertThat(header.getOrEmpty(DicomTag.PATIENT_NAME)).isEqualTo("Noorani^Farida");
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("CR");
        assertThat(header.getOrEmpty(DicomTag.BODY_PART_EXAMINED)).isEqualTo("CHEST");
        // Read from their binary representation, not from text.
        assertThat(header.getInt(DicomTag.ROWS)).contains(2048);
        assertThat(header.getInt(DicomTag.COLUMNS)).contains(2500);
        assertThat(header.transferSyntaxUid()).isEqualTo(EXPLICIT_VR_LE);
    }

    @Test
    @DisplayName("implicit VR has no types in it, and is read by tag instead")
    void readsImplicitVr() {
        // The oldest and still commonest syntax off older modalities. Every element is a tag and a
        // length, and what it holds is knowable only from a dictionary.
        byte[] file = new DicomWriter(IMPLICIT_VR_LE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-IMPLICIT")
                .str(DicomTag.MODALITY, "CS", "US")
                .str(DicomTag.STUDY_INSTANCE_UID, "UI", STUDY_UID)
                .uint16(DicomTag.ROWS, 512)
                .pixels(32)
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-IMPLICIT");
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("US");
        assertThat(header.getOrEmpty(DicomTag.STUDY_INSTANCE_UID)).isEqualTo(STUDY_UID);
        assertThat(header.getInt(DicomTag.ROWS)).contains(512);
    }

    @Test
    @DisplayName("the file meta group is little endian even when the dataset is not")
    void readsBigEndianDataset() {
        // The trap: the header that *declares* big endian is itself little endian, so a parser that
        // switched too early reads the transfer syntax with the wrong rules and never recovers.
        byte[] file = new DicomWriter(EXPLICIT_VR_BE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-BIGENDIAN")
                .str(DicomTag.MODALITY, "CS", "MR")
                .uint16(DicomTag.ROWS, 256)
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        assertThat(header.transferSyntaxUid()).isEqualTo(EXPLICIT_VR_BE);
        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-BIGENDIAN");
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("MR");
        // 256 written big endian is 0x01 0x00; read little endian it would be 1.
        assertThat(header.getInt(DicomTag.ROWS)).contains(256);
    }

    @Test
    @DisplayName("a compressed syntax still has a plain dataset, and is read like one")
    void readsACompressedFile() {
        // JPEG, JPEG 2000 and RLE compress the pixels and leave the dataset as explicit VR little
        // endian. A parser that refused an unknown transfer syntax would refuse most of radiology.
        byte[] file = new DicomWriter(JPEG_BASELINE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-JPEG")
                .str(DicomTag.MODALITY, "CS", "XA")
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        assertThat(header.transferSyntaxUid()).isEqualTo(JPEG_BASELINE);
        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-JPEG");
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("XA");
    }

    @Test
    @DisplayName("a sequence of unknown length is stepped over, not fallen into")
    void skipsASequenceOfUnknownLength() {
        byte[] file = new DicomWriter(EXPLICIT_VR_LE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-SEQ")
                // Referenced study sequence, written with an undefined length and closed by a
                // delimiter — legal, common, and the shape that hangs a reader expecting a count.
                .undefinedLengthSequence(DicomTag.of(0x0008, 0x1110))
                .str(DicomTag.MODALITY, "CS", "CT")
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-SEQ");
        // The element after the sequence is still found, which is what proves it was stepped over
        // rather than consumed as data.
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("CT");
    }

    @Test
    @DisplayName("a file cut short keeps what it had read, because a truncated transfer is common")
    void survivesTruncation() {
        byte[] whole = new DicomWriter(EXPLICIT_VR_LE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-TRUNC")
                .str(DicomTag.MODALITY, "CS", "CT")
                .str(DicomTag.STUDY_INSTANCE_UID, "UI", STUDY_UID)
                .pixels(4096)
                .build();
        byte[] cut = new byte[whole.length / 2];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        DicomParser.DicomHeader header = DicomParser.parse(cut);

        // Everything read before the cut is still true. Throwing away a study's identity because
        // the transfer stopped would be losing the one thing that identifies what to re-request.
        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-TRUNC");
        assertThat(header.getOrEmpty(DicomTag.MODALITY)).isEqualTo("CT");
    }

    @Test
    @DisplayName("something that is not a DICOM file is refused, and the message says why")
    void refusesWhatIsNotDicom() {
        byte[] jpeg = new byte[200];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;

        assertThatThrownBy(() -> DicomParser.parse(jpeg))
                .isInstanceOf(DicomParser.DicomParseException.class)
                // The usual cause is the wrong file, and the usual fix is obvious once somebody is
                // told that "DICM" was not where it should be.
                .hasMessageContaining("DICM");

        assertThatThrownBy(() -> DicomParser.parse(new byte[10]))
                .isInstanceOf(DicomParser.DicomParseException.class);
        assertThatThrownBy(() -> DicomParser.parse(null))
                .isInstanceOf(DicomParser.DicomParseException.class);
    }

    @Test
    @DisplayName("padding is not content")
    void trimsPadding() {
        // DICOM pads a value to an even length. An accession number of "ACC-1 " does not match the
        // order whose number is "ACC-1", and a study that matched nothing is a study nobody reports.
        byte[] file = new DicomWriter(EXPLICIT_VR_LE)
                .str(DicomTag.ACCESSION_NUMBER, "SH", "ACC-1")
                .str(DicomTag.STUDY_INSTANCE_UID, "UI", STUDY_UID)
                .build();

        DicomParser.DicomHeader header = DicomParser.parse(file);

        assertThat(header.getOrEmpty(DicomTag.ACCESSION_NUMBER)).isEqualTo("ACC-1");
        // A UID is padded with a null rather than a space, which is a different character and the
        // same mistake.
        assertThat(header.getOrEmpty(DicomTag.STUDY_INSTANCE_UID)).isEqualTo(STUDY_UID);
    }

    // ---- a minimal Part 10 writer, for these tests only ------------------------

    /**
     * Writes the DICOM structures these cases need, and nothing else.
     *
     * <p>Not a general encoder. It exists so the parser can be tested against real bytes in the
     * real layouts without a fifteen-megabyte fixture in the repository.
     */
}
