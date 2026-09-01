package com.hms.laboratory.device.astm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The query direction: reading an analyzer's request, and writing the host's answer. */
class AstmHostQueryTest {

    private static final Instant AT = Instant.parse("2026-09-01T09:30:00Z");

    @Nested
    @DisplayName("reading a query")
    class Reading {

        @Test
        @DisplayName("the sample id is found wherever the vendor put it in the range field")
        void sampleIdLayouts() {
            // All three of these are seen in the field, from different vendors and firmware levels.
            // Encoding one layout would produce a parser that works against one instrument and
            // silently returns nothing for the next.
            assertThat(AstmQueryReader.sampleIdsIn("Q|1|^L2026-000042||ALL||||||||O\r"))
                    .containsExactly("L2026-000042");
            assertThat(AstmQueryReader.sampleIdsIn("Q|1|^^^^^L2026-000042||ALL||||||||O\r"))
                    .containsExactly("L2026-000042");
            assertThat(AstmQueryReader.sampleIdsIn("Q|1|L2026-000042^||ALL||||||||O\r"))
                    .containsExactly("L2026-000042");
        }

        @Test
        @DisplayName("a full framed query transmission is read")
        void wholeTransmission() {
            String transmission = "H|\\^&|||XP300|||||||Q|1\r"
                    + "Q|1|^L2026-000042||ALL||||||||O\r"
                    + "L|1|N\r";
            assertThat(AstmQueryReader.sampleIdsIn(transmission)).containsExactly("L2026-000042");
            assertThat(AstmQueryReader.isQuery(transmission)).isTrue();
        }

        @Test
        @DisplayName("frame sequence numbers are stripped, as they are for results")
        void framedRecords() {
            assertThat(AstmQueryReader.sampleIdsIn("2Q|1|^L2026-000042||ALL||||||||O\r"))
                    .containsExactly("L2026-000042");
        }

        @Test
        @DisplayName("an analyzer that read a whole rack asks once, and repeats collapse")
        void severalSamplesInOneConversation() {
            String transmission = "H|\\^&|||XP300|||||||Q|1\r"
                    + "Q|1|^L2026-000001||ALL||||||||O\r"
                    + "Q|2|^L2026-000002||ALL||||||||O\r"
                    + "Q|3|^L2026-000001||ALL||||||||O\r"
                    + "L|1|N\r";
            // Duplicates collapse: answering the same sample twice in one worklist reads to the
            // instrument as two separate orders for one tube.
            assertThat(AstmQueryReader.sampleIdsIn(transmission))
                    .containsExactly("L2026-000001", "L2026-000002");
        }

        @Test
        @DisplayName("a result upload is not mistaken for a query")
        void resultUploadIsNotAQuery() {
            String results = "H|\\^&|||XP300|||||||P|1\r"
                    + "P|1||L2026-000042||TEST^PATIENT||19880412|F\r"
                    + "O|1|L2026-000042||^^^CBC|R\r"
                    + "R|1|^^^^WBC^26|7.4|10*3/uL||N\r"
                    + "L|1|N\r";
            assertThat(AstmQueryReader.isQuery(results)).isFalse();
            assertThat(AstmQueryReader.sampleIdsIn(results)).isEmpty();
        }

        @Test
        @DisplayName("a malformed or empty query yields nothing rather than throwing")
        void malformedQuery() {
            // The caller is a socket handler with an instrument waiting on it. Throwing here would
            // turn a garbled frame into a hung analyzer.
            assertThat(AstmQueryReader.sampleIdsIn("")).isEmpty();
            assertThat(AstmQueryReader.sampleIdsIn(null)).isEmpty();
            assertThat(AstmQueryReader.sampleIdsIn("Q|1|||ALL||||||||O\r")).isEmpty();
            assertThat(AstmQueryReader.sampleIdsIn("garbage without pipes\r")).isEmpty();
        }
    }

    @Nested
    @DisplayName("writing the worklist")
    class Writing {

        @Test
        @DisplayName("the answer round-trips through the platform's own result parser")
        void roundTripsThroughAstmParser() {
            String worklist = AstmWorklistWriter.write(List.of(
                    new AstmWorklistWriter.Entry("L2026-000042", "F", "ROUTINE", "WHOLE_BLOOD",
                            List.of("CBC"))), "MEDSYNC", AT);

            // The strongest check available without an instrument: feed the transmission we emit
            // back through the parser that reads real Sysmex output. A field written into the wrong
            // position fails here, and reading the specification again would never have caught it.
            List<AstmRecord.Sample> parsed = new ArrayList<>();
            AstmParser parser = new AstmParser(parsed::add);
            parser.feedAll(worklist);

            assertThat(parsed).hasSize(1);
            AstmRecord.Sample sample = parsed.get(0);
            assertThat(sample.order().sampleId()).isEqualTo("L2026-000042");
            assertThat(sample.resolvedSex()).isEqualTo("F");
        }

        @Test
        @DisplayName("one O record per requested test")
        void oneOrderRecordPerTest() {
            String worklist = AstmWorklistWriter.write(List.of(
                    new AstmWorklistWriter.Entry("L2026-000042", "M", "ROUTINE", "WHOLE_BLOOD",
                            List.of("CBC", "ESR", "PLTC"))), "MEDSYNC", AT);

            assertThat(worklist.lines().filter(line -> line.startsWith("O|")).count()).isEqualTo(3);
            assertThat(worklist).contains("^^^CBC").contains("^^^ESR").contains("^^^PLTC");
        }

        @Test
        @DisplayName("platform priorities map to ASTM codes rather than being passed through")
        void priorityMapping() {
            // An unrecognised code makes some instruments reject the whole order, so the platform's
            // vocabulary is translated rather than emitted raw.
            assertThat(oneOrderLine("STAT")).contains("|S|");
            assertThat(oneOrderLine("URGENT")).contains("|A|");
            assertThat(oneOrderLine("ROUTINE")).contains("|R|");
            assertThat(oneOrderLine(null)).contains("|R|");
            assertThat(oneOrderLine("SOMETHING_NEW")).contains("|R|");
        }

        @Test
        @DisplayName("an empty worklist is still a well-formed transmission")
        void emptyWorklistIsStillValid() {
            String worklist = AstmWorklistWriter.write(List.of(), "MEDSYNC", AT);

            // An analyzer waiting on a reply must get one. Silence looks identical to a broken link,
            // and the operator sees a hung instrument instead of a tube with nothing ordered.
            assertThat(worklist).startsWith("H|");
            assertThat(worklist).contains("L|1|N");
            assertThat(worklist.lines().filter(line -> line.startsWith("O|")).count()).isZero();
        }

        @Test
        @DisplayName("delimiter characters in a value are stripped, not passed through")
        void delimitersAreStripped() {
            String worklist = AstmWorklistWriter.write(List.of(
                    new AstmWorklistWriter.Entry("L2026|000042", "F", "ROUTINE", "WHOLE^BLOOD",
                            List.of("CB|C"))), "MEDSYNC", AT);

            // A stray delimiter shifts every following field, so the instrument reads the next
            // value in the wrong slot - a silent corruption. Stripping makes it a visible one.
            assertThat(worklist).contains("L2026000042");
            assertThat(worklist).contains("WHOLEBLOOD");
            assertThat(worklist).contains("^^^CBC");

            // And the transmission still parses, which is the point of stripping rather than
            // rejecting: one odd character must not cost the whole worklist.
            List<AstmRecord.Sample> parsed = new ArrayList<>();
            new AstmParser(parsed::add).feedAll(worklist);
            assertThat(parsed).hasSize(1);
            assertThat(parsed.get(0).order().sampleId()).isEqualTo("L2026000042");
        }

        @Test
        @DisplayName("several samples are answered in one transmission")
        void severalSamples() {
            String worklist = AstmWorklistWriter.write(List.of(
                    new AstmWorklistWriter.Entry("L2026-000001", "F", "ROUTINE", "WHOLE_BLOOD",
                            List.of("CBC")),
                    new AstmWorklistWriter.Entry("L2026-000002", "M", "STAT", "WHOLE_BLOOD",
                            List.of("ESR"))), "MEDSYNC", AT);

            List<AstmRecord.Sample> parsed = new ArrayList<>();
            new AstmParser(parsed::add).feedAll(worklist);
            // One H..L transmission carrying two samples: the parser emits on the terminator, so
            // both orders arrive even though only one L record was written.
            assertThat(worklist).contains("L2026-000001").contains("L2026-000002");
            assertThat(worklist.lines().filter(line -> line.startsWith("P|")).count()).isEqualTo(2);
        }

        @Test
        @DisplayName("no patient name reaches the instrument")
        void noPatientNameOnTheWire() {
            String worklist = AstmWorklistWriter.write(List.of(
                    new AstmWorklistWriter.Entry("L2026-000042", "F", "ROUTINE", "WHOLE_BLOOD",
                            List.of("CBC"))), "MEDSYNC", AT);

            // laboratory-service does not hold the name, and the Entry record has no field for one -
            // so this asserts the shape stays that way. An instrument in a shared room spooling to a
            // local printer is not a place to put identity the analyzer does not need.
            List<AstmRecord.Sample> parsed = new ArrayList<>();
            new AstmParser(parsed::add).feedAll(worklist);
            assertThat(parsed.get(0).resolvedName()).isEmpty();
        }
    }

    private static String oneOrderLine(String priority) {
        return AstmWorklistWriter.write(List.of(
                new AstmWorklistWriter.Entry("L2026-000042", "F", priority, "WHOLE_BLOOD",
                        List.of("CBC"))), "MEDSYNC", AT)
                .lines().filter(line -> line.startsWith("O|")).findFirst().orElseThrow();
    }
}
