package com.hms.interop.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The wire format, tested against the shapes real interfaces send rather than the shapes the
 * standard draws.
 *
 * <p>Every message here is one an engine actually meets: a Windows sender's CRLF line endings, a
 * result with repeating OBX segments, an escaped surname, a message split across two TCP reads. The
 * standard is unambiguous about all of it and the field is not, which is why these are the cases.
 */
class Hl7CodecTest {

    private static final String CR = "\r";

    private static final String ADT_A01 = String.join(CR,
            "MSH|^~\\&|LIS|CENTRAL LAB|HMS|CITY HOSPITAL|20260903120000||ADT^A01|MSG00001|P|2.5",
            "EVN|A01|20260903115900",
            "PID|1||MRN-2026-000010^^^HMS^MR||Noorani^Farida^B||19780412|F|||"
                    + "12 Park Road^^Kolkata^WB^700016^IN||9876543210",
            "PV1|1|I|WARD1^101^A||||1234^Rao^Anil|||MED");

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads the header, the type and the control id the acknowledgement must echo")
        void readsTheHeader() {
            Hl7Message message = Er7Parser.parse(ADT_A01);

            assertThat(message.messageType()).isEqualTo("ADT^A01");
            assertThat(message.controlId()).isEqualTo("MSG00001");
            assertThat(message.sendingApplication()).isEqualTo("LIS");
            assertThat(message.sendingFacility()).isEqualTo("CENTRAL LAB");
            assertThat(message.receivingApplication()).isEqualTo("HMS");
            assertThat(message.processingId()).isEqualTo("P");
            assertThat(message.versionId()).isEqualTo("2.5");
        }

        @Test
        @DisplayName("MSH counts its fields differently, and both segments agree on field 3")
        void mshIsOffByOneAndItIsHandled() {
            Hl7Message message = Er7Parser.parse(ADT_A01);
            Hl7Segment msh = message.segment("MSH").orElseThrow();
            Hl7Segment pid = message.segment("PID").orElseThrow();

            // MSH-1 is the separator itself and MSH-2 the encoding characters, so MSH-3 is the
            // third token where PID-3 is the fourth. This is the classic off-by-one, and the point
            // of testing both in one place is that field(3) means the right thing on each.
            assertThat(msh.field(1)).isEqualTo("|");
            assertThat(msh.field(2)).isEqualTo("^~\\&");
            assertThat(msh.field(3)).isEqualTo("LIS");
            assertThat(pid.component(3, 1)).isEqualTo("MRN-2026-000010");
            assertThat(pid.component(3, 4)).isEqualTo("HMS");
            assertThat(pid.component(5, 1)).isEqualTo("Noorani");
            assertThat(pid.component(5, 2)).isEqualTo("Farida");
        }

        @Test
        @DisplayName("a component that is not there is empty rather than an exception")
        void missingFieldsAreEmpty() {
            Hl7Segment pid = Er7Parser.parse(ADT_A01).segment("PID").orElseThrow();

            assertThat(pid.field(99)).isEmpty();
            assertThat(pid.component(5, 9)).isEmpty();
            assertThat(pid.component(99, 1)).isEmpty();
            // A message that stops early is the normal case, not an error: reading past the end
            // must not throw, or every optional field needs a length check at its call site.
            assertThat(Er7Parser.parse(ADT_A01).segment("EVN").orElseThrow().field(6)).isEmpty();
        }

        @Test
        @DisplayName("CRLF, bare LF and trailing blank lines all parse")
        void toleratesRealWorldLineEndings() {
            String windows = ADT_A01.replace(CR, "\r\n") + "\r\n\r\n";
            String unixish = ADT_A01.replace(CR, "\n");

            assertThat(Er7Parser.parse(windows).segments()).hasSize(4);
            assertThat(Er7Parser.parse(unixish).segments()).hasSize(4);
            assertThat(Er7Parser.parse(windows).messageType()).isEqualTo("ADT^A01");
        }

        @Test
        @DisplayName("a message with no header is refused rather than guessed at")
        void refusesAMessageWithNoHeader() {
            // Named in the refusal: a batch header at an endpoint expecting single messages is a
            // configuration mistake somebody can fix in a minute if the error says "FHS".
            assertThatThrownBy(() -> Er7Parser.parse("FHS|^~\\&|SENDER" + CR + "MSH|^~\\&|X"))
                    .isInstanceOf(Er7Parser.Hl7ParseException.class)
                    .hasMessageContaining("FHS");

            assertThatThrownBy(() -> Er7Parser.parse("   "))
                    .isInstanceOf(Er7Parser.Hl7ParseException.class);
        }

        @Test
        @DisplayName("a sender's own delimiters are read from the message, not assumed")
        void readsNonStandardDelimiters() {
            // Legal, rare, and fatal to a parser that hard-codes the usual five.
            String odd = "MSH!@~\\&!LIS!LAB!HMS!HOSP!20260903120000!!ADT@A04!M1!P!2.5" + CR
                    + "PID!1!!MRN-9!!Smith@John";
            Hl7Message message = Er7Parser.parse(odd);

            assertThat(message.encoding().field()).isEqualTo('!');
            assertThat(message.encoding().component()).isEqualTo('@');
            assertThat(message.messageType()).isEqualTo("ADT^A04");
            assertThat(message.segment("PID").orElseThrow().component(5, 2)).isEqualTo("John");
        }

        @Test
        @DisplayName("repeating segments are all read, not just the first")
        void readsEveryRepeatingSegment() {
            String oru = String.join(CR,
                    "MSH|^~\\&|LAB|CENTRAL|HMS|CITY|20260903120000||ORU^R01|R1|P|2.5",
                    "PID|1||MRN-2026-000010",
                    "OBR|1||ACC-1|CBC^Full blood count",
                    "OBX|1|NM|HGB^Haemoglobin||9.4|g/dL|13.0-17.0|L|||F",
                    "OBX|2|NM|WBC^White cells||12.8|10*3/uL|4.0-11.0|H|||F",
                    "OBX|3|NM|PLT^Platelets||140|10*3/uL|150-410|L|||F");

            List<Hl7Segment> results = Er7Parser.parse(oru).allSegments("OBX");
            // Reading only the first OBX is a whole blood count reduced to its haemoglobin.
            assertThat(results).hasSize(3);
            assertThat(results.get(1).component(3, 1)).isEqualTo("WBC");
            assertThat(results.get(1).field(5)).isEqualTo("12.8");
            assertThat(results.get(2).field(8)).isEqualTo("L");
        }

        @Test
        @DisplayName("a field that repeats is read as its repetitions")
        void readsFieldRepetitions() {
            String pid = "MSH|^~\\&|A|B|C|D|20260903120000||ADT^A08|M|P|2.5" + CR
                    + "PID|1||MRN-1~NHS-999^^^NHS||Doe^Jane";
            Hl7Segment segment = Er7Parser.parse(pid).segment("PID").orElseThrow();

            assertThat(segment.repetitions(3)).containsExactly("MRN-1", "NHS-999^^^NHS");
            // The plain accessor takes the first, which is what a caller wanting "the MRN" means.
            assertThat(segment.component(3, 1)).isEqualTo("MRN-1");
        }
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        @Test
        @DisplayName("a delimiter inside a value survives the round trip")
        void roundTripsDelimiters() {
            Hl7Encoding encoding = Hl7Encoding.DEFAULT;
            for (String original : List.of("O^Brien", "A|B", "50% & rising", "back\\slash",
                    "one~two", "plain")) {
                assertThat(Er7Parser.unescape(Er7Parser.escape(original, encoding), encoding))
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("an escaped name is decoded, because the escape is not part of the name")
        void decodesAnEscapedName() {
            String message = "MSH|^~\\&|A|B|C|D|20260903120000||ADT^A08|M|P|2.5" + CR
                    + "PID|1||MRN-1||O\\S\\Brien^Se\\T\\an";
            Hl7Message parsed = Er7Parser.parse(message);
            Hl7Segment pid = parsed.segment("PID").orElseThrow();

            // Writing "O\S\Brien" into a chart is a defect reported as a corrupted name.
            assertThat(Er7Parser.unescape(pid.component(5, 1), parsed.encoding()))
                    .isEqualTo("O^Brien");
            assertThat(Er7Parser.unescape(pid.component(5, 2), parsed.encoding()))
                    .isEqualTo("Se&an");
        }

        @Test
        @DisplayName("display instructions are dropped and an unterminated escape is kept")
        void handlesTheAwkwardEscapes() {
            Hl7Encoding encoding = Hl7Encoding.DEFAULT;

            // \H\ and \N\ turn highlighting on and off. Rendered literally they are worse than
            // ignored, and they are not content.
            assertThat(Er7Parser.unescape("\\H\\URGENT\\N\\ result", encoding))
                    .isEqualTo("URGENT result");
            assertThat(Er7Parser.unescape("\\X0D\\", encoding)).isEqualTo(CR);
            // Not an escape sequence at all: a lone backslash is data.
            assertThat(Er7Parser.unescape("50\\ percent", encoding)).isEqualTo("50\\ percent");
        }
    }

    @Nested
    @DisplayName("timestamps")
    class Timestamps {

        @Test
        @DisplayName("read to any precision the sender chose to send")
        void readsEveryPrecision() {
            assertThat(Hl7Message.parseTimestamp("20260903120000")).isPresent();
            assertThat(Hl7Message.parseTimestamp("20260903")).isPresent();
            assertThat(Hl7Message.parseTimestamp("202609031200.5")).isPresent();
            assertThat(Hl7Message.parseTimestamp("20260903120000+0530")).isPresent();

            // The offset is applied rather than ignored: noon in Kolkata is 06:30 UTC.
            assertThat(Hl7Message.parseTimestamp("20260903120000+0530").orElseThrow())
                    .isEqualTo(Hl7Message.parseTimestamp("20260903063000").orElseThrow());
        }

        @Test
        @DisplayName("nonsense is empty rather than an exception in the middle of a message")
        void refusesNonsenseQuietly() {
            assertThat(Hl7Message.parseTimestamp("")).isEmpty();
            assertThat(Hl7Message.parseTimestamp("not a date")).isEmpty();
            assertThat(Hl7Message.parseTimestamp("20261345999999")).isEmpty();
        }
    }

    @Nested
    @DisplayName("MLLP")
    class Mllp {

        @Test
        @DisplayName("a message split across reads is assembled, not lost")
        void assemblesAcrossReads() {
            MllpFraming framing = new MllpFraming();
            byte[] whole = MllpFraming.frame(ADT_A01, StandardCharsets.UTF_8);

            // TCP delivers bytes, not messages. Split at an arbitrary point mid-payload.
            int split = whole.length / 3;
            assertThat(framing.feed(whole, split, StandardCharsets.UTF_8)).isEmpty();
            assertThat(framing.pending()).isGreaterThan(0);

            byte[] rest = new byte[whole.length - split];
            System.arraycopy(whole, split, rest, 0, rest.length);
            List<String> complete = framing.feed(rest, rest.length, StandardCharsets.UTF_8);

            assertThat(complete).hasSize(1);
            assertThat(Er7Parser.parse(complete.get(0)).controlId()).isEqualTo("MSG00001");
        }

        @Test
        @DisplayName("two messages in one read are both returned")
        void returnsEveryCompleteMessage() {
            MllpFraming framing = new MllpFraming();
            byte[] one = MllpFraming.frame(ADT_A01, StandardCharsets.UTF_8);
            byte[] both = new byte[one.length * 2];
            System.arraycopy(one, 0, both, 0, one.length);
            System.arraycopy(one, 0, both, one.length, one.length);

            // Dropping the second is a result that is never filed.
            assertThat(framing.feed(both, both.length, StandardCharsets.UTF_8)).hasSize(2);
        }

        @Test
        @DisplayName("a stream resynchronises after a sender dies mid-message")
        void resynchronisesAfterGarbage() {
            MllpFraming framing = new MllpFraming();
            byte[] good = MllpFraming.frame(ADT_A01, StandardCharsets.UTF_8);

            // Half a message, no terminator, then a whole one: what a killed sender leaves behind.
            byte[] truncated = "MSH|^~\\&|LIS|LAB|HMS".getBytes(StandardCharsets.UTF_8);
            framing.feed(truncated, truncated.length, StandardCharsets.UTF_8);
            List<String> complete = framing.feed(good, good.length, StandardCharsets.UTF_8);

            // Without discarding to the next start byte, every message after the first bad one is
            // corrupt — the failure mode that takes an interface down until somebody restarts it.
            assertThat(complete).hasSize(1);
            assertThat(Er7Parser.parse(complete.get(0)).messageType()).isEqualTo("ADT^A01");
        }

        @Test
        @DisplayName("the frame is the three bytes and nothing else")
        void framesExactly() {
            byte[] framed = MllpFraming.frame("MSH|x", StandardCharsets.UTF_8);

            assertThat(framed[0]).isEqualTo(MllpFraming.VT);
            assertThat(framed[framed.length - 2]).isEqualTo(MllpFraming.FS);
            assertThat(framed[framed.length - 1]).isEqualTo(MllpFraming.CR);
        }
    }

    @Nested
    @DisplayName("acknowledgement")
    class Acknowledgement {

        @Test
        @DisplayName("echoes the control id and addresses the reply back to the sender")
        void addressesTheReplyBack() {
            Hl7Message message = Er7Parser.parse(ADT_A01);
            Hl7Message ack = Er7Parser.parse(
                    Hl7Ack.of(message, Hl7Ack.Code.AA, null, "HMS", "CITY HOSPITAL"));

            assertThat(ack.messageType()).isEqualTo("ACK");
            // Swapped: an acknowledgement that echoes the original addressing is discarded by
            // anything that checks who it is for.
            assertThat(ack.sendingApplication()).isEqualTo("HMS");
            assertThat(ack.receivingApplication()).isEqualTo("LIS");
            assertThat(ack.receivingFacility()).isEqualTo("CENTRAL LAB");

            Hl7Segment msa = ack.segment("MSA").orElseThrow();
            assertThat(msa.field(1)).isEqualTo("AA");
            // The sender matches its message by this and nothing else.
            assertThat(msa.field(2)).isEqualTo("MSG00001");
        }

        @Test
        @DisplayName("a refusal carries its reason on one line")
        void carriesTheReason() {
            Hl7Message message = Er7Parser.parse(ADT_A01);
            String text = "No patient matches MRN-2026-000010\nand none was created";
            Hl7Message ack = Er7Parser.parse(
                    Hl7Ack.of(message, Hl7Ack.Code.AE, text, "HMS", "CITY"));

            Hl7Segment msa = ack.segment("MSA").orElseThrow();
            assertThat(msa.field(1)).isEqualTo("AE");
            // A newline inside the field would end the segment early at the receiver, turning an
            // explanation into a malformed acknowledgement.
            assertThat(msa.field(3)).doesNotContain("\n").doesNotContain(CR);
            assertThat(msa.field(3)).contains("No patient matches");
        }

        @Test
        @DisplayName("a message that never parsed is rejected with an empty control id")
        void rejectsWithoutAControlId() {
            Hl7Message ack = Er7Parser.parse(
                    Hl7Ack.rejected("The first segment is not MSH", "HMS", "CITY"));

            Hl7Segment msa = ack.segment("MSA").orElseThrow();
            // AR, not AE: the sender may usefully try again. And no control id, because the header
            // that carries it is the thing that was missing.
            assertThat(msa.field(1)).isEqualTo("AR");
            assertThat(msa.field(2)).isEmpty();
        }
    }
}
