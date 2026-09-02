package com.hms.billing.event;

import com.hms.billing.domain.BillingEnums.ChargeSource;
import com.hms.billing.service.BillingConfigService;
import com.hms.billing.service.InvoiceService;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.error.ConflictException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.Topics;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Charge capture: the reason no clinical service in this platform knows billing exists.
 *
 * <p>A consultation completed, a report released, a medicine handed over and a stay ended are all
 * clinical facts that happen to cost money. Asking scheduling to call billing would make finishing
 * a consultation fail when the billing service is down, which is the wrong trade in a hospital.
 * So the clinical services publish what happened and this listener prices it, and if it is down
 * the charges are captured when it comes back.
 *
 * <p><strong>Replay is the normal case, not the exception.</strong> Brokers redeliver, and an
 * operator recovering a lost partition replays a day. Every charge here goes through
 * {@link InvoiceService#postCharge}, whose {@code posted_charges} key is
 * {@code (sourceType, sourceId, chargeItemCode)} — so the second delivery of an event writes
 * nothing and says so. That primary key, not the care taken in this class, is what stops a patient
 * being billed twice.
 *
 * <p><strong>An unpriced charge is reported, never guessed.</strong> When an event names something
 * the price list has never heard of, this listener says so in the log and moves on. The
 * alternatives are worse: throwing would stall the consumer group and stop every later charge, and
 * substituting a plausible price would put a number on an invoice that nobody chose.
 *
 * <p>Built to the shape {@code AuditEventListener} established: only active with Kafka, a raw
 * {@code String} payload deserialised by hand, and a malformed message logged and dropped so one
 * bad message cannot stall the group forever.
 */
@Component
@ConditionalOnProperty(name = "hms.events.transport", havingValue = "kafka")
public class ChargeCaptureListener {

    private static final Logger log = LoggerFactory.getLogger(ChargeCaptureListener.class);

    private final InvoiceService invoices;
    private final BillingConfigService config;
    private final ObjectMapper objectMapper;
    private final String consultationCode;
    private final String casualtyCode;
    private final String labFallbackCode;
    private final String labPrefix;
    private final String dispenseFallbackCode;
    private final String bedDayCode;

    public ChargeCaptureListener(InvoiceService invoices, BillingConfigService config,
            ObjectMapper objectMapper,
            @Value("${hms.billing.capture.consultation-code:CONSULT_OP}") String consultationCode,
            @Value("${hms.billing.capture.casualty-code:CASUALTY}") String casualtyCode,
            @Value("${hms.billing.capture.lab-fallback-code:LAB_PANEL}") String labFallbackCode,
            @Value("${hms.billing.capture.lab-code-prefix:LAB_}") String labPrefix,
            @Value("${hms.billing.capture.dispense-fallback-code:PHARM_DISP}")
            String dispenseFallbackCode,
            @Value("${hms.billing.capture.bed-day-code:BED_GEN}") String bedDayCode) {
        this.invoices = invoices;
        this.config = config;
        this.objectMapper = objectMapper;
        this.consultationCode = consultationCode;
        this.casualtyCode = casualtyCode;
        this.labFallbackCode = labFallbackCode;
        this.labPrefix = labPrefix;
        this.dispenseFallbackCode = dispenseFallbackCode;
        this.bedDayCode = bedDayCode;
    }

    @KafkaListener(topics = Topics.APPOINTMENT, groupId = "billing-charge-capture-appointments")
    public void onAppointment(String message) {
        handle(message, event -> {
            if (!"appointment.completed".equals(event.type())) {
                return Map.of();
            }
            // A completed consultation, once. Not a booked one: a patient who never arrived owes
            // nothing, and a no-show fee is a policy decision this platform does not make for a
            // deployment.
            return Map.of(consultationCode, BigDecimal.ONE);
        }, ChargeSource.APPOINTMENT, "consultation");
    }

    @KafkaListener(topics = Topics.LAB, groupId = "billing-charge-capture-lab")
    public void onLab(String message) {
        handle(message, event -> {
            if (!"lab.results.verified".equals(event.type())) {
                return Map.of();
            }
            // Released, not ordered. A test that was ordered and cancelled was never done, and
            // billing at order time would charge for it — which is why this listens to the release
            // even though the order event is the one that names the tests first.
            Map<String, BigDecimal> quantities = new LinkedHashMap<>();
            for (String test : strings(event.payload().get("tests"))) {
                String code = config.chargeable(labPrefix + test).isPresent()
                        ? labPrefix + test
                        : labFallbackCode;
                // Summed rather than overwritten. Two tests with no charge item of their own both
                // fall back to the panel code, and the posted-charges key would collapse them into
                // one line — so the quantity has to carry the count or the second test is free.
                quantities.merge(code, BigDecimal.ONE, BigDecimal::add);
            }
            return quantities;
        }, ChargeSource.LAB_ORDER, "released laboratory order");
    }

    @KafkaListener(topics = Topics.PHARMACY, groupId = "billing-charge-capture-pharmacy")
    public void onPharmacy(String message) {
        handle(message, event -> {
            if (!"pharmacy.dispensed".equals(event.type())) {
                return Map.of();
            }
            String drug = text(event.payload().get("drugCode"));
            BigDecimal quantity = number(event.payload().get("quantity"));
            if (drug == null || quantity == null || quantity.signum() <= 0) {
                return Map.of();
            }
            // A dispensed medicine is priced by the charge list, and a deployment that wants each
            // drug billed at its own price creates a charge item whose code is the drug's. Until
            // it does, the dispensing item carries the charge — visible on the invoice naming the
            // drug, rather than absent from it. Named in the README's gaps.
            String code = config.chargeable(drug).isPresent() ? drug : dispenseFallbackCode;
            return Map.of(code, quantity);
        }, ChargeSource.DISPENSE, "dispense");
    }

    @KafkaListener(topics = Topics.ADMISSION, groupId = "billing-charge-capture-admissions")
    public void onAdmission(String message) {
        handle(message, event -> {
            if ("admission.discharged".equals(event.type())) {
                // The bed-day count travels on the discharge event, which is why a stay is billed
                // when it ends rather than by a nightly job counting occupied beds: one event, one
                // idempotent charge, and no clock to be wrong about.
                BigDecimal days = number(event.payload().get("bedDays"));
                return days == null || days.signum() <= 0
                        ? Map.of() : Map.of(bedDayCode, days);
            }
            if ("casualty.discharged".equals(event.type())) {
                return Map.of(casualtyCode, BigDecimal.ONE);
            }
            // Deliberately not casualty.left: a patient who left without being seen was not
            // treated, and billing an attendance fee for a wait is indefensible.
            return Map.of();
        }, ChargeSource.ADMISSION, "attendance");
    }

    /**
     * The one path every topic goes through.
     *
     * <p>Deserialise, ask the caller what to charge, then post each charge in its own transaction.
     * Nothing here decides what anything costs — that is the charge list's job — and nothing here
     * decides whether a charge is a duplicate, which is the database's.
     */
    private void handle(String message, ChargeRule rule, ChargeSource source, String what) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            Map<String, BigDecimal> quantities = rule.chargesFor(event);
            if (quantities.isEmpty()) {
                return;
            }
            UUID patientId = uuid(event.payload().get("patientId"));
            String mrn = text(event.payload().get("mrn"));
            if (patientId == null || mrn == null) {
                log.warn("Event {} ({}) carries no patient; {} not charged", event.eventId(),
                        event.type(), what);
                return;
            }
            UUID sourceId = uuid(event.aggregateId());
            if (sourceId == null) {
                // The aggregate id is half of the key that stops a double charge. Without one,
                // posting would create a charge no replay could recognise as a duplicate — which
                // is worse than not charging, because the second delivery would bill again.
                log.warn("Event {} ({}) names no aggregate to charge against", event.eventId(),
                        event.type());
                return;
            }
            quantities.forEach((code, qty) ->
                    post(event, sourceId, source, code, qty, patientId, mrn, what));
        } catch (RuntimeException ex) {
            // Dropped rather than retried forever. A message this consumer cannot read will not
            // become readable, and a stalled group means every later charge goes uncaptured.
            log.error("Discarding unreadable event for charge capture: {}", ex.getMessage());
        }
    }

    private void post(DomainEvent event, UUID sourceId, ChargeSource source, String code,
                      BigDecimal qty, UUID patientId, String mrn, String what) {
        Optional<com.hms.billing.domain.ChargeItem> item = config.chargeable(code);
        if (item.isEmpty()) {
            log.warn("No active charge item '{}' — the {} from event {} is not charged. Add the "
                    + "charge item and replay the event.", code, what, event.eventId());
            return;
        }
        try {
            BillingDtos.PostChargeResponse answer = invoices.postCharge(
                    new BillingDtos.PostChargeRequest(source, sourceId, patientId, mrn,
                            null, null, code, qty, item.get().getName()));
            if (answer.alreadyPosted()) {
                log.debug("Charge {} for {} {} was already posted", code, source, sourceId);
            }
        } catch (ConflictException ex) {
            // Two deliveries in flight at once, and the other one won. Exactly the outcome wanted.
            log.debug("Charge {} for {} {} lost a race and was already posted", code, source,
                    sourceId);
        } catch (RuntimeException ex) {
            log.error("Could not charge {} for {} {}: {}", code, source, sourceId,
                    ex.getMessage());
        }
    }

    /** What an event should be charged: charge item code to quantity, empty for "nothing". */
    private interface ChargeRule {
        Map<String, BigDecimal> chargesFor(DomainEvent event);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(text -> text.trim().toUpperCase(Locale.ROOT))
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static UUID uuid(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
