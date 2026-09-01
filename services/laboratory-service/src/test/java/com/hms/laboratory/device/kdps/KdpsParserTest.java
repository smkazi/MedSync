package com.hms.laboratory.device.kdps;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.laboratory.device.astm.Histogram;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the K-DPS port against real frames captured from a Sysmex RX-21/XP-100 over the wire.
 *
 * <p>The fixtures are the byte-for-byte captures used by the original Python suite
 * (smkazi/HaematologyIS), for two different patients — so these tests prove the decoder recovers a
 * distinct per-patient distribution from the actual wire format, not from a reconstruction.
 */
class KdpsParserTest {

    private static byte[] patientAHistogram;
    private static byte[] patientBHistogram;
    private static byte[] patientAHeader;

    @BeforeAll
    static void loadFixtures() throws IOException {
        patientAHistogram = fixture("kdps-histogram-patient-a.hex");
        patientBHistogram = fixture("kdps-histogram-patient-b.hex");
        patientAHeader = fixture("kdps-header-patient-a.hex");
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = KdpsParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture %s must be on the test classpath", name).isNotNull();
            return KdpsParser.hexToBytes(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
    }

    /** Wraps a payload in transport framing, as the serial or TCP layer delivers it. */
    private static byte[] framed(byte[] payload) {
        byte[] out = new byte[payload.length + 2];
        out[0] = 0x02;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[out.length - 1] = 0x03;
        return out;
    }

    @Nested
    @DisplayName("framing")
    class Framing {

        @Test
        @DisplayName("framing is stripped without touching binary payload bytes")
        void stripsFramingWithoutDamagingPayload() {
            // A K-DPS payload legitimately contains 0x02 and 0x03; scanning for control bytes
            // would truncate real channel data.
            byte[] payload = {0x02, 0x10, 0x03, 0x20, 0x02, 0x30};

            assertThat(KdpsParser.stripFraming(framed(payload))).containsExactly(payload);
        }

        @Test
        @DisplayName("an unframed payload passes through unchanged")
        void unframedPayloadIsUnchanged() {
            byte[] payload = {0x41, 0x42, 0x43};
            assertThat(KdpsParser.stripFraming(payload)).containsExactly(payload);
        }
    }

    @Nested
    @DisplayName("header frame")
    class HeaderFrame {

        @Test
        @DisplayName("carries the full 19-parameter CBC row")
        void headerCarriesNumericResults() {
            KdpsParser.Header header = KdpsParser.parseHeader(patientAHeader);

            assertThat(header).isNotNull();
            assertThat(header.results()).hasSize(19);

            Map<String, String> values = header.results().stream()
                    .collect(java.util.stream.Collectors.toMap(KdpsSample.KdpsResult::parameter,
                            KdpsSample.KdpsResult::value));
            assertThat(values)
                    .containsEntry("WBC", "6.3")
                    .containsEntry("RBC", "2.73")
                    .containsEntry("HGB", "7.2")
                    .containsEntry("PLT", "130")
                    .containsEntry("MCV", "71.8")
                    .containsEntry("NEUT%", "49.1")
                    .containsEntry("MPV", "11.3");
        }

        @Test
        @DisplayName("reads the patient name and measurement time")
        void headerCarriesIdentityAndTime() {
            KdpsParser.Header header = KdpsParser.parseHeader(patientAHeader);

            assertThat(header.identifier()).isEqualTo("GEETA VITKAR");
            assertThat(header.measuredAt()).isEqualTo("2026-01-29 12:47");
            assertThat(header.dateKey()).isEqualTo("202601291247");
        }

        @Test
        @DisplayName("results carry the analyzer's units")
        void resultsCarryUnits() {
            Map<String, String> units = KdpsParser.parseHeader(patientAHeader).results().stream()
                    .collect(java.util.stream.Collectors.toMap(KdpsSample.KdpsResult::parameter,
                            KdpsSample.KdpsResult::unit));

            assertThat(units).containsEntry("WBC", "10^3/uL").containsEntry("HGB", "g/dL")
                    .containsEntry("MCV", "fL").containsEntry("NEUT%", "%");
        }

        @Test
        @DisplayName("a frame without the date stamp is not a header")
        void nonHeaderFrameIsRejected() {
            assertThat(KdpsParser.looksLikeHeader("not a header at all".getBytes(StandardCharsets.US_ASCII)))
                    .isFalse();
            assertThat(KdpsParser.parseHeader("not a header".getBytes(StandardCharsets.US_ASCII))).isNull();
        }

        @Test
        @DisplayName("unmeasurable fields are dropped, not stored as their mask symbols")
        void maskedFieldsAreDropped() {
            assertThat(KdpsParser.numericField("*0000")).isNull();
            assertThat(KdpsParser.numericField("----")).isNull();
            assertThat(KdpsParser.numericField("******")).isNull();
            assertThat(KdpsParser.numericField("+++")).isNull();
            assertThat(KdpsParser.numericField("     ")).isNull();
            assertThat(KdpsParser.numericField(" 6.3 ")).isEqualTo("6.3");
        }
    }

    @Nested
    @DisplayName("histogram frame")
    class HistogramFrame {

        @Test
        @DisplayName("splits into the three cell groups on a volume axis")
        void splitsIntoThreeGroups() {
            Map<String, Histogram> curves = KdpsParser.parseHistogramFrame(patientAHistogram);

            assertThat(curves).containsOnlyKeys("WBC", "PLT", "RBC");
            for (String group : List.of("WBC", "PLT", "RBC")) {
                assertThat(curves.get(group).y()).as("%s curve", group).isNotEmpty();
                assertThat(curves.get(group).xLabel()).isEqualTo(Histogram.VOLUME_AXIS);
                assertThat(curves.get(group).y().stream().mapToDouble(Double::doubleValue).max().orElse(0))
                        .as("%s curve is peak-normalised to 100", group)
                        .isEqualTo(100.0);
            }
        }

        @Test
        @DisplayName("the WBC curve has the three-part shape the analyzer prints")
        void wbcCurveIsThreePartShaped() {
            List<Double> wbc = KdpsParser.parseHistogramFrame(patientAHistogram).get("WBC").y();

            // A three-part differential shows a lymphocyte peak, a valley, then a granulocyte bump.
            double lymphocytePeak = wbc.subList(0, 20).stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double valley = wbc.subList(14, 22).stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double granulocyteBump = wbc.subList(22, 36).stream().mapToDouble(Double::doubleValue).max().orElse(0);

            assertThat(lymphocytePeak).isEqualTo(100.0);
            assertThat(valley).isLessThan(lymphocytePeak);
            assertThat(granulocyteBump).isGreaterThan(valley);
        }

        @Test
        @DisplayName("the RBC block's fixed end marker is dropped")
        void rbcEndMarkerIsDropped() {
            // The segment is 40 channels and closes with two fixed 100s that are not measured data.
            assertThat(KdpsParser.parseHistogramFrame(patientAHistogram).get("RBC").y()).hasSize(38);
        }

        @Test
        @DisplayName("two patients decode to genuinely different curves")
        void curvesArePatientSpecific() {
            Map<String, Histogram> a = KdpsParser.parseHistogramFrame(patientAHistogram);
            Map<String, Histogram> b = KdpsParser.parseHistogramFrame(patientBHistogram);

            for (String group : List.of("WBC", "PLT", "RBC")) {
                assertThat(a.get(group).y())
                        .as("%s curve must be this patient's, not a template", group)
                        .isNotEqualTo(b.get(group).y());
            }
        }

        @Test
        @DisplayName("a text frame of small numbers is not mistaken for a histogram")
        void textFrameIsNotAHistogram() {
            // The ASCII-hex D2/D3 graphic records are all-printable and would otherwise look like
            // 150+ small channel values.
            byte[] asciiHex = "0102030405060708".repeat(12).getBytes(StandardCharsets.US_ASCII);

            assertThat(asciiHex.length).isBetween(150, 260);
            assertThat(KdpsParser.looksLikeHistogram(asciiHex))
                    .as("no NUL byte and no trailer signature: this is text, not a histogram")
                    .isFalse();
        }

        @Test
        @DisplayName("a real capture is recognised as a histogram")
        void realFrameIsRecognised() {
            assertThat(KdpsParser.looksLikeHistogram(patientAHistogram)).isTrue();
            assertThat(KdpsParser.looksLikeHistogram(patientBHistogram)).isTrue();
        }
    }

    @Nested
    @DisplayName("whole transmission")
    class WholeTransmission {

        @Test
        @DisplayName("pairs each header with the histogram frame that follows it")
        void pairsHeadersWithHistograms() {
            List<KdpsSample> samples = KdpsParser.parseFrames(List.of(
                    patientAHeader, framed(patientAHistogram),
                    patientAHeader, framed(patientBHistogram)));

            assertThat(samples).hasSize(2);
            assertThat(samples.get(0).identifier()).isEqualTo("GEETA VITKAR");
            assertThat(samples.get(0).histograms()).containsOnlyKeys("WBC", "PLT", "RBC");
            assertThat(samples.get(0).results()).hasSize(19);
            assertThat(samples.get(1).histograms().get("WBC").y())
                    .isNotEqualTo(samples.get(0).histograms().get("WBC").y());
        }

        @Test
        @DisplayName("a histogram with no readable header still yields its curves")
        void orphanHistogramStillDecodes() {
            // Losing the header must not lose the graph: it can still be matched by time or position.
            List<KdpsSample> samples = KdpsParser.parse(framed(patientAHistogram));

            assertThat(samples).hasSize(1);
            assertThat(samples.get(0).identifier()).isEmpty();
            assertThat(samples.get(0).histograms()).containsOnlyKeys("WBC", "PLT", "RBC");
        }

        @Test
        @DisplayName("decodes from a raw-capture log")
        void decodesFromCaptureLog() {
            String log = """
                    2026-01-29 12:47:01 RX hex=%s | ascii=...
                    2026-01-29 12:47:02 RX hex=%s | ascii=...
                    """.formatted(toHex(patientAHeader), toHex(framed(patientAHistogram)));

            List<KdpsSample> samples = KdpsParser.parseCaptureText(log);

            assertThat(samples).hasSize(1);
            assertThat(samples.get(0).identifier()).isEqualTo("GEETA VITKAR");
            assertThat(samples.get(0).histograms()).containsOnlyKeys("WBC", "PLT", "RBC");
        }

        @Test
        @DisplayName("an empty transmission yields no samples")
        void emptyTransmissionYieldsNothing() {
            assertThat(KdpsParser.parseFrames(List.of())).isEmpty();
            assertThat(KdpsParser.parseCaptureText("no frames here")).isEmpty();
        }

        private String toHex(byte[] data) {
            StringBuilder hex = new StringBuilder(data.length * 2);
            for (byte b : data) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }
}
