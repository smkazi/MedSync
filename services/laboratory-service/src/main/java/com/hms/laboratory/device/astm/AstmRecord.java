package com.hms.laboratory.device.astm;

import java.util.List;

/** The parsed forms of the ASTM record types this platform consumes. */
public final class AstmRecord {

    private AstmRecord() {
    }

    /** H — header: who is sending and in what version. */
    public record Header(String sender, String processingId, String version) {
    }

    /**
     * P — patient. Field positions differ between the ASTM standard and what Sysmex analyzers
     * actually transmit, so both layouts are attempted; see {@link AstmRecordParser}.
     *
     * @param patientId analyzer-supplied patient or sample id; blank rather than fabricated
     * @param sex       normalised to {@code M} or {@code F}, defaulting to {@code M}
     */
    public record Patient(String patientId, String name, Integer age, String sex, String dateOfBirthRaw,
                         String referringDoctor) {

        public static Patient empty() {
            return new Patient("", "", null, "M", "", "");
        }
    }

    /** O — order. On a Poch-100i the patient name and sex arrive packed into this record. */
    public record Order(String sampleId, String name, String sex, String collected) {

        public static Order empty() {
            return new Order("", "", "M", "");
        }

        public boolean isEmpty() {
            return sampleId.isBlank() && name.isBlank() && collected.isBlank();
        }
    }

    /**
     * Q — query. The analyzer asking the host what is ordered for a sample it has just read.
     *
     * <p>This is the record that makes the link bidirectional. Without it the only traffic is the
     * analyzer pushing results and a technician keying the worklist into the instrument by hand.
     *
     * @param sampleId    the specimen the analyzer wants orders for; the accession number
     * @param universalId the requested test set, conventionally {@code ALL}
     * @param statusCode  request information status, conventionally {@code O} for orders
     */
    public record Query(String sampleId, String universalId, String statusCode) {

        public boolean isEmpty() {
            return sampleId == null || sampleId.isBlank();
        }
    }

    /** R — result: one measured parameter. */
    public record Result(String parameter, String value, String unit, String normalRange, Double normalLow,
                        Double normalHigh, String flag) {

        /** A result whose value the analyzer could not measure carries no number. */
        public boolean hasValue() {
            return value != null && !value.isBlank();
        }

        public Result withFlag(String newFlag) {
            return new Result(parameter, value, unit, normalRange, normalLow, normalHigh, newFlag);
        }
    }

    /** C — comment. On some analyzers these carry histogram arrays. */
    public record Comment(String type, String text) {
    }

    /**
     * A complete transmission between H and L: one sample's identity, order, results and comments.
     */
    public record Sample(Patient patient, Order order, List<Result> results, List<Comment> comments) {

        /** Copied on the way in: a parsed transmission is measured patient data, not a buffer. */
        public Sample {
            results = List.copyOf(results);
            comments = List.copyOf(comments);
        }

        /**
         * The identifier to file this sample under: the P record's id, else the O record's sample
         * number. Blank when the analyzer sent neither — the caller then allocates an accession
         * number rather than inventing an id that could collide with another sample.
         */
        public String resolvedSampleId() {
            if (!patient.patientId().isBlank()) {
                return patient.patientId();
            }
            return order.sampleId();
        }

        /** Name from the P record, falling back to the O record (where a Poch-100i puts it). */
        public String resolvedName() {
            return patient.name().isBlank() ? order.name() : patient.name();
        }

        /** Sex from the P record, falling back to the O record, defaulting to {@code M}. */
        public String resolvedSex() {
            String sex = patient.sex();
            if (sex == null || sex.isBlank()) {
                sex = order.sex();
            }
            return (sex == null || sex.isBlank()) ? "M" : sex;
        }
    }
}
