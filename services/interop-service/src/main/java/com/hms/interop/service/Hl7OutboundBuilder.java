package com.hms.interop.service;

import com.hms.interop.hl7.Er7Builder;
import com.hms.interop.hl7.Hl7Encoding;
import com.hms.interop.web.dto.InteropDtos;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the two messages this platform has something to say with.
 *
 * <p>ADT^A04 tells another system a patient exists, and ORU^R01 tells it what their results were.
 * Between them they are most of what a hospital sends outward, and they are the two the platform
 * actually holds the data for — an ORM would be this platform ordering work from somebody else,
 * which nothing here does yet, so it is not built.
 *
 * <p>Every value is escaped by {@link Er7Builder}, which is not a detail: a name with a caret in it
 * written raw shifts every later field one place left, and the receiver files a valid-looking
 * message in which the date of birth is in the sex field.
 */
@Component
public class Hl7OutboundBuilder {

    private static final Hl7Encoding ENCODING = Hl7Encoding.DEFAULT;

    /**
     * The control id counter.
     *
     * <p>Unique per message and not a sequence in the database: a control id identifies one
     * transmission so a receiver can acknowledge it, and it carries none of the auditable meaning
     * an invoice number does. A restart repeating one is harmless; a shared table lock on the
     * sending path would not be.
     */
    private final AtomicLong counter = new AtomicLong();

    private final String application;
    private final String facility;
    private final String processingId;

    public Hl7OutboundBuilder(@Value("${hms.interop.hl7.application:HMS}") String application,
                              @Value("${hms.interop.hl7.facility:HMS}") String facility,
                              @Value("${hms.interop.hl7.processing-id:P}") String processingId) {
        this.application = application;
        this.facility = facility;
        this.processingId = processingId;
    }

    /**
     * ADT^A04 — this patient has been registered.
     *
     * <p>A04 rather than A01: A01 is an admission, which is a different clinical event, and sending
     * one for an outpatient registration tells the receiving system somebody is occupying a bed.
     */
    public String registration(InteropDtos.Hl7PatientView patient, String receivingApplication,
                               String receivingFacility) {
        Er7Builder builder = new Er7Builder(ENCODING);
        builder.header(application, facility, receivingApplication, receivingFacility,
                "ADT^A04", nextControlId(), processingId, "2.5");
        builder.segment("EVN", builder.field("A04"),
                builder.field(Er7Builder.timestamp(Instant.now())));
        appendPid(builder, patient);
        return builder.build();
    }

    /**
     * ORU^R01 — here are the results for one order.
     *
     * <p>OBX-11 is {@code F} for final, which is the only status this platform sends: a result
     * leaves here after a pathologist has verified it, and there is no path that transmits a
     * provisional one. Sending {@code P} for something already released would be a lie in the
     * direction that gets acted on.
     */
    public String results(InteropDtos.Hl7PatientView patient, InteropDtos.Hl7OrderView order,
                          String receivingApplication, String receivingFacility) {
        Er7Builder builder = new Er7Builder(ENCODING);
        builder.header(application, facility, receivingApplication, receivingFacility,
                "ORU^R01", nextControlId(), processingId, "2.5");
        appendPid(builder, patient);

        builder.segment("OBR",
                builder.field("1"),
                builder.field(order.placerOrderNumber()),
                builder.field(order.accessionNumber()),
                builder.components(order.panelCode(), order.panelName()),
                builder.field(""),
                builder.field(""),
                builder.field(Er7Builder.timestamp(order.collectedAt())),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(Er7Builder.timestamp(order.verifiedAt())),
                builder.field(""),
                builder.field("F"));

        int index = 1;
        for (InteropDtos.Hl7ResultView result : order.results()) {
            builder.segment("OBX",
                    builder.field(String.valueOf(index++)),
                    // NM for a number, ST for anything else. A receiver that is told NM and given
                    // "not detected" may refuse the whole message rather than the one result.
                    builder.field(isNumeric(result.value()) ? "NM" : "ST"),
                    builder.components(result.code(), result.name()),
                    builder.field(""),
                    builder.field(result.value()),
                    builder.field(result.units()),
                    builder.field(result.referenceRange()),
                    builder.field(result.abnormalFlag()),
                    builder.field(""),
                    builder.field(""),
                    builder.field("F"));
        }
        return builder.build();
    }

    /** PID, shared by both messages so the two cannot disagree about how a patient is written. */
    private void appendPid(Er7Builder builder, InteropDtos.Hl7PatientView patient) {
        builder.segment("PID",
                builder.field("1"),
                builder.field(""),
                // The MRN with its assigning authority and identifier type: a bare id in PID-3 is
                // ambiguous the moment a receiver takes patients from two hospitals.
                builder.components(patient.mrn(), "", "", facility, "MR"),
                builder.field(""),
                builder.components(patient.familyName(), patient.givenName()),
                builder.field(""),
                builder.field(patient.dateOfBirth()),
                builder.field(patient.sex()),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(""),
                builder.field(patient.phone()));
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String nextControlId() {
        return "HMS" + Er7Builder.timestamp(Instant.now()) + counter.incrementAndGet();
    }

    /** The message types this builder can produce, for a caller validating a request. */
    public List<String> supported() {
        return List.of("ADT^A04", "ORU^R01");
    }
}
