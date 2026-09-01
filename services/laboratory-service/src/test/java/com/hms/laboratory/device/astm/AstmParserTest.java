package com.hms.laboratory.device.astm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ASTM port against the same cases the original Python implementation was tested
 * with, plus the analyzer quirks its comments document.
 */
class AstmParserTest {

    /** A complete Poch-100i transmission, as the original test suite used it. */
    private static final String POCH_TRANSMISSION =
            "H|\\^&|||POCH100I|||||||P|1\r"
            + "P|1||PAT001||CHANDRAKANT^RAWAL||19660101|M|||||||Dr. ROHIT DIXIT\r"
            + "O|1|PAT001||^^^CBC|R\r"
            + "R|1|^^^WBC^1|8.3|10*3/uL|4.0-11.0|N\r"
            + "R|2|^^^RBC^1|4.61|10*6/uL|4.5-6.5|N\r"
            + "R|3|^^^HGB^1|13.5|g/dL|13.5-18.0|N\r"
            + "R|4|^^^HCT^1|38.6|%|40.0-54.0|L\r"
            + "R|5|^^^PLT^1|271.0|10*3/uL|150.0-450.0|N\r"
            + "L|1|N\r";

    private static Map<String, AstmRecord.Result> byParameter(AstmRecord.Sample sample) {
        return sample.results().stream()
                .collect(Collectors.toMap(AstmRecord.Result::parameter, Function.identity(),
                        (first, second) -> first));
    }

    private static AstmRecord.Result parseResultLine(String line) {
        return AstmRecordParser.parseResult(AstmRecordParser.splitFields(line));
    }

    @Nested
    @DisplayName("full transmission")
    class FullTransmission {

        @Test
        @DisplayName("emits one sample at the terminator record with all results")
        void emitsSampleAtTerminator() {
            List<AstmRecord.Sample> samples = AstmParser.parseAll(POCH_TRANSMISSION);

            assertThat(samples).hasSize(1);
            AstmRecord.Sample sample = samples.get(0);
            assertThat(sample.resolvedSampleId()).isEqualTo("PAT001");
            assertThat(sample.resolvedSex()).isEqualTo("M");
            assertThat(sample.results()).hasSize(5);
        }

        @Test
        @DisplayName("keeps the values and the analyzer's own flags")
        void keepsValuesAndAnalyzerFlags() {
            Map<String, AstmRecord.Result> results = byParameter(AstmParser.parseAll(POCH_TRANSMISSION).get(0));

            assertThat(results.get("HGB").value()).isEqualTo("13.5");
            assertThat(results.get("HCT").flag())
                    .as("a flag the analyzer supplied must be preserved verbatim")
                    .isEqualTo("L");
            assertThat(results.get("WBC").normalLow()).isEqualTo(4.0);
            assertThat(results.get("WBC").normalHigh()).isEqualTo(11.0);
        }

        @Test
        @DisplayName("reads the patient name, date of birth and referring doctor")
        void readsPatientIdentity() {
            AstmRecord.Sample sample = AstmParser.parseAll(POCH_TRANSMISSION).get(0);

            assertThat(sample.resolvedName()).isEqualTo("CHANDRAKANT RAWAL");
            assertThat(sample.patient().dateOfBirthRaw()).isEqualTo("19660101");
            assertThat(sample.patient().referringDoctor()).isEqualTo("Dr. ROHIT DIXIT");
            assertThat(sample.patient().age()).isNotNull().isGreaterThan(50);
        }

        @Test
        @DisplayName("a transmission with no terminator emits nothing while streaming")
        void incompleteTransmissionEmitsNothing() {
            java.util.List<AstmRecord.Sample> emitted = new java.util.ArrayList<>();
            AstmParser parser = new AstmParser(emitted::add);

            parser.feedFrame("H|\\^&|||POCH100I|||||||P|1\rP|1||PAT001||X^Y||19900101|M\r");

            assertThat(emitted)
                    .as("a sample is only complete at its L record")
                    .isEmpty();
        }

        @Test
        @DisplayName("a header-only keep-alive does not create an empty sample")
        void headerOnlyTransmissionIsIgnored() {
            assertThat(AstmParser.parseAll("H|\\^&|||POCH100I|||||||P|1\rL|1|N\r")).isEmpty();
        }

        @Test
        @DisplayName("several samples in one transmission are all emitted")
        void multipleSamplesInOneTransmission() {
            String two = POCH_TRANSMISSION
                    + "H|\\^&|||POCH100I|||||||P|1\r"
                    + "P|1||PAT002|SECOND^PATIENT||19800101|F\r"
                    + "O|1|PAT002||^^^CBC|R\r"
                    + "R|1|^^^WBC^1|5.5|10*3/uL|4.0-11.0|N\r"
                    + "L|1|N\r";

            List<AstmRecord.Sample> samples = AstmParser.parseAll(two);

            assertThat(samples).hasSize(2);
            assertThat(samples.get(1).resolvedSampleId()).isEqualTo("PAT002");
            assertThat(samples.get(1).resolvedSex()).isEqualTo("F");
        }

        @Test
        @DisplayName("records arriving split across frames still assemble into one sample")
        void recordsSplitAcrossFramesAssemble() {
            java.util.List<AstmRecord.Sample> emitted = new java.util.ArrayList<>();
            AstmParser parser = new AstmParser(emitted::add);

            parser.feedFrame("H|\\^&|||POCH100I|||||||P|1\r");
            parser.feedFrame("P|1||PAT007||SPLIT^FRAME||19900101|M\r");
            parser.feedFrame("R|1|^^^WBC^1|7.7|10*3/uL|4.0-11.0|N\r");
            parser.feedFrame("L|1|N\r");

            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).resolvedSampleId()).isEqualTo("PAT007");
            assertThat(emitted.get(0).results()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Poch-100i identity quirks")
    class PochIdentityQuirks {

        @Test
        @DisplayName("name and sex are recovered from the O record when the P record is empty")
        void mergesIdentityFromOrderRecord() {
            // A Poch-100i sends an empty P record and packs the name and sex into O field 3.
            String transmission =
                    "H|\\^&|||POCH100I|||||||P|1\r"
                    + "P|1\r"
                    + "O|1||^^          KAMAL^M|^^^^WBC|20260101120000\r"
                    + "R|1|^^^WBC^1|9.1|10*3/uL|4.0-11.0|N\r"
                    + "L|1|N\r";

            AstmRecord.Sample sample = AstmParser.parseAll(transmission).get(0);

            assertThat(sample.resolvedName()).isEqualTo("KAMAL");
            assertThat(sample.resolvedSex()).isEqualTo("M");
            assertThat(sample.order().collected()).isEqualTo("20260101120000");
        }

        @Test
        @DisplayName("an XN-330 sample sequence number in the order record becomes the sample id")
        void sampleSequenceFromOrderRecord() {
            String transmission =
                    "H|\\^&|||XN330|||||||P|1\r"
                    + "P|1\r"
                    + "O|1||^^        12^M|^^^^CBC|R\r"
                    + "R|1|^^^WBC^1|6.0|10*3/uL|4.0-11.0|N\r"
                    + "L|1|N\r";

            assertThat(AstmParser.parseAll(transmission).get(0).resolvedSampleId()).isEqualTo("12");
        }

        @Test
        @DisplayName("an unidentified sample gets a blank id rather than a fabricated one")
        void unidentifiedSampleHasBlankId() {
            String transmission =
                    "H|\\^&|||POCH100I|||||||P|1\r"
                    + "P|1\r"
                    + "O|1|||^^^^CBC|R\r"
                    + "R|1|^^^WBC^1|6.0|10*3/uL|4.0-11.0|N\r"
                    + "L|1|N\r";

            assertThat(AstmParser.parseAll(transmission).get(0).resolvedSampleId())
                    .as("two unidentified samples must not collide under a shared placeholder id")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("result record")
    class ResultRecord {

        @Test
        @DisplayName("XP three-part W- codes map to the display names")
        void mapsXpParameterCodes() {
            assertThat(parseResultLine("R|5|^^^^W-SCR^26|26.9|%||N").parameter()).isEqualTo("LYM%");
            assertThat(parseResultLine("R|6|^^^^W-MCR^26|5.7|%||N").parameter()).isEqualTo("MXD%");
            assertThat(parseResultLine("R|7|^^^^W-LCR^26|67.4|%||N").parameter()).isEqualTo("NEUT%");
            assertThat(parseResultLine("R|8|^^^^W-SCC^26|2.6|10*3/uL||N").parameter()).isEqualTo("LYM#");
            assertThat(parseResultLine("R|9|^^^^W-MCC^26|0.6|10*3/uL||N").parameter()).isEqualTo("MXD#");
            assertThat(parseResultLine("R|10|^^^^W-LCC^26|6.8|10*3/uL||N").parameter()).isEqualTo("NEUT#");
        }

        @Test
        @DisplayName("the trailing dilution ratio is not mistaken for the parameter name")
        void dilutionRatioIsNotTheName() {
            assertThat(parseResultLine("R|1|^^^^WBC^26|78|10*2/uL||N").parameter()).isEqualTo("WBC");
        }

        @Test
        @DisplayName("masked and out-of-range symbols become no value at all")
        void maskedValuesBecomeBlank() {
            assertThat(parseResultLine("R|3|^^^^HGB^26|***.*|g/dL||A").value())
                    .as("a masked result carries no number, so it must not be stored as ***.*")
                    .isEmpty();
            assertThat(parseResultLine("R|9|^^^^WBC^26|+++.+|10*2/uL||H").value()).isEmpty();
            assertThat(parseResultLine("R|4|^^^^PLT^26|---.-|10*3/uL||L").value()).isEmpty();
            assertThat(parseResultLine("R|2|^^^^RBC^26|3.50|10*6/uL||N").value()).isEqualTo("3.50");
        }

        @Test
        @DisplayName("a reference range is parsed with either dash character")
        void parsesReferenceRange() {
            AstmRecord.Result hyphen = parseResultLine("R|1|^^^WBC^1|8.3|10*3/uL|4.0-11.0|N");
            assertThat(hyphen.normalLow()).isEqualTo(4.0);
            assertThat(hyphen.normalHigh()).isEqualTo(11.0);

            AstmRecord.Result enDash = parseResultLine("R|1|^^^WBC^1|8.3|10*3/uL|4.0 – 11.0|N");
            assertThat(enDash.normalLow()).isEqualTo(4.0);
            assertThat(enDash.normalHigh()).isEqualTo(11.0);
        }

        @Test
        @DisplayName("an unparseable reference range leaves the bounds unset")
        void unparseableRangeLeavesBoundsUnset() {
            AstmRecord.Result result = parseResultLine("R|1|^^^WBC^1|8.3|10*3/uL|see report|N");
            assertThat(result.normalLow()).isNull();
            assertThat(result.normalHigh()).isNull();
        }
    }

    @Nested
    @DisplayName("age parsing")
    class AgeParsing {

        @Test
        @DisplayName("accepts every age format the analyzers send")
        void parsesEveryAgeFormat() {
            assertThat(AstmRecordParser.parseAge("058Y")).isEqualTo(58);
            assertThat(AstmRecordParser.parseAge("58Y")).isEqualTo(58);
            assertThat(AstmRecordParser.parseAge("045M")).isEqualTo(45);
            assertThat(AstmRecordParser.parseAge("028F")).isEqualTo(28);
            assertThat(AstmRecordParser.parseAge("58")).isEqualTo(58);
        }

        @Test
        @DisplayName("derives age from a full date of birth")
        void derivesAgeFromDateOfBirth() {
            int expected = java.time.LocalDate.now().getYear() - 1990
                    - (java.time.LocalDate.now().isBefore(
                            java.time.LocalDate.of(java.time.LocalDate.now().getYear(), 6, 15)) ? 1 : 0);

            assertThat(AstmRecordParser.parseAge("19900615")).isEqualTo(expected);
        }

        @Test
        @DisplayName("returns nothing rather than guessing when the field is unusable")
        void unusableAgeIsNull() {
            assertThat(AstmRecordParser.parseAge("")).isNull();
            assertThat(AstmRecordParser.parseAge(null)).isNull();
            assertThat(AstmRecordParser.parseAge("00000000")).isNull();
        }
    }

    @Nested
    @DisplayName("frame helpers")
    class FrameHelpers {

        @Test
        @DisplayName("a correct checksum verifies")
        void correctChecksumVerifies() {
            String body = "P|1|";
            int sum = 0;
            for (byte b : body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                sum += b & 0xFF;
            }
            String frame = body + String.format("%02X", sum % 256);

            assertThat(AstmFrames.verifyChecksum(frame.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .isTrue();
        }

        @Test
        @DisplayName("a wrong checksum fails")
        void wrongChecksumFails() {
            assertThat(AstmFrames.verifyChecksum("P|1|00".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .isFalse();
        }

        @Test
        @DisplayName("unverifiable frames are accepted rather than discarded")
        void unverifiableFramesAreAccepted() {
            // Discarding a frame loses a patient result; analyzers in the field send both forms.
            assertThat(AstmFrames.verifyChecksum("ab".getBytes())).isTrue();
            assertThat(AstmFrames.verifyChecksum("garbage".getBytes())).isTrue();
            assertThat(AstmFrames.verifyChecksum(null)).isTrue();
        }

        @Test
        @DisplayName("a leading frame sequence number is stripped")
        void stripsFrameSequenceNumber() {
            assertThat(AstmFrames.stripFrameNumber("2R|1|^^^WBC")).isEqualTo("R|1|^^^WBC");
            assertThat(AstmFrames.stripFrameNumber("R|1|^^^WBC")).isEqualTo("R|1|^^^WBC");
            assertThat(AstmFrames.stripFrameNumber("")).isEmpty();
        }
    }
}
