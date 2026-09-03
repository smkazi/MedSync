package com.hms.interop.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.security.CurrentUser;
import com.hms.interop.client.AbdmGateway;
import com.hms.interop.client.ClinicalDataClient;
import com.hms.interop.client.PortalClinicalClient;
import com.hms.interop.client.dto.ClinicalViews.EncounterView;
import com.hms.interop.client.dto.ClinicalViews.LabOrderView;
import com.hms.interop.client.dto.ClinicalViews.PatientView;
import com.hms.interop.client.dto.ClinicalViews.PrescriptionView;
import com.hms.interop.domain.ConsentArtefact;
import com.hms.interop.domain.Disclosure;
import com.hms.interop.domain.InteropEnums.DisclosureKind;
import com.hms.interop.domain.InteropEnums.HiType;
import com.hms.interop.repo.DisclosureRepository;
import com.hms.interop.web.dto.InteropDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * What leaves the building, and the two paths it can leave by.
 *
 * <p><strong>A consented share</strong> goes to somebody outside this deployment and cannot happen
 * without a consent artefact that covers the information type and the record's own date. The check
 * is {@link ConsentService#authorise}, it runs before anything is read, and there is no argument
 * that skips it.
 *
 * <p><strong>A patient export</strong> hands a person their own record, which is the EHI-export
 * criterion rather than a disclosure to a third party. It has no consent behind it deliberately:
 * asking somebody to consent to receiving their own data is a formality, and formalities are how
 * people learn to click through consent screens. It is instead narrowly authorised and loudly
 * audited, because "somebody exported an entire chart" is exactly the event an investigation looks
 * for.
 *
 * <p>Both paths write a {@link Disclosure} in the same transaction as the release. Not afterwards
 * and not from a log: the accounting of disclosures is the answer to "who has seen my record", and
 * an answer assembled later from six services' logs is not an answer.
 */
@Service
public class ExchangeService {

    private final ConsentService consents;
    private final ClinicalDataClient clinical;
    private final PortalClinicalClient portal;
    private final DisclosureRepository disclosures;
    private final AbdmGateway gateway;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;
    private final FhirBundleBuilder fhir;

    public ExchangeService(ConsentService consents, ClinicalDataClient clinical,
                           PortalClinicalClient portal,
                           DisclosureRepository disclosures, AbdmGateway gateway,
                           AuditService audit, ObjectMapper objectMapper,
                           @Value("${hms.interop.facility-name:An unnamed clinical establishment}")
                           String facilityName,
                           @Value("${hms.interop.facility-id:UNSET}") String facilityId,
                           @Value("${hms.interop.zone:Asia/Kolkata}") ZoneId zone) {
        this.consents = consents;
        this.clinical = clinical;
        this.portal = portal;
        this.disclosures = disclosures;
        this.gateway = gateway;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.zone = zone;
        this.fhir = new FhirBundleBuilder(facilityName, facilityId);
    }

    /**
     * Builds one record's bundle and sends it under a consent.
     *
     * <p>The order is the design: authorise, then read, then build, then send, then record. Reading
     * the chart before checking the consent would mean a refused request had already pulled the
     * data into this service's memory, which is the kind of detail that decides whether a
     * disclosure happened.
     */
    @Transactional
    public InteropDtos.ShareResponse share(InteropDtos.ShareRequest request, String bearerToken) {
        // Consent first, in two halves. Everything that can be decided without touching the
        // record — granted, live, covers this kind of information — is decided before anything is
        // read, so a revoked consent never causes a chart to be fetched at all. Only then is the
        // record read, for its date, and only then is the covered period checked.
        ConsentArtefact consent = consents.authorise(request.artefactId(), request.hiType());
        Dated record = dateOf(request.hiType(), request.recordId(), bearerToken);
        consents.assertCovers(consent, record.date());

        if (!consent.getPatientId().equals(record.patientId())) {
            throw new ConflictException(("Consent %s is for %s and this record belongs to somebody "
                    + "else. A consent is permission about one patient, not a key to the archive.")
                    .formatted(request.artefactId(), consent.getPatientMrn()));
        }

        PatientView patient = clinical.patient(consent.getPatientId(), bearerToken);
        Map<String, Object> bundle = build(request.hiType(), patient, record, bearerToken);
        byte[] serialised = objectMapper.writeValueAsBytes(bundle);

        AbdmGateway.Outcome outcome = gateway.send(bundle, consent.getRequester(),
                consent.getArtefactId());

        Disclosure disclosure = disclosures.save(new Disclosure(consent.getId(),
                consent.getPatientId(), consent.getPatientMrn(), request.hiType(),
                DisclosureKind.CONSENTED_SHARE, consent.getRequester(), countOf(bundle),
                serialised.length, CurrentUser.usernameOrSystem()));

        audit.record("HEALTH_INFORMATION_SHARED", "Disclosure", disclosure.getId(),
                "%s about %s to %s under consent %s (%s)".formatted(request.hiType(),
                        consent.getPatientMrn(), consent.getRequester(), consent.getArtefactId(),
                        outcome.transmitted() ? "transmitted" : "not transmitted"));

        return new InteropDtos.ShareResponse(disclosure.getId(), consent.getArtefactId(),
                request.hiType(), countOf(bundle), serialised.length, outcome.transmitted(),
                outcome.name(), outcome.detail());
    }

    /**
     * A patient's own record, as a bundle of bundles.
     *
     * <p>The EHI-export criterion: everything the platform holds about one person, in a machine-
     * readable form, on request. A {@code searchset} of documents rather than one enormous
     * document, because that is what a receiving system can iterate and a person can inspect.
     *
     * <p>What it contains is bounded by what the caller may read, since every underlying call
     * carries their token. An export run by somebody who cannot read charts is an export of
     * demographics, which is the correct outcome rather than a failure.
     */
    @Transactional
    public Map<String, Object> exportForPatient(UUID patientId, List<UUID> encounterIds,
                                                List<UUID> labOrderIds,
                                                List<UUID> prescriptionIds, String bearerToken) {
        PatientView patient = clinical.patient(patientId, bearerToken);
        List<Map<String, Object>> documents = new ArrayList<>();

        for (UUID id : encounterIds) {
            documents.add(fhir.opConsultation(patient,
                    clinical.encounter(id, bearerToken)));
        }
        for (UUID id : labOrderIds) {
            documents.add(fhir.diagnosticReport(patient, clinical.labOrder(id, bearerToken)));
        }
        for (UUID id : prescriptionIds) {
            documents.add(fhir.prescription(patient, clinical.prescription(id, bearerToken)));
        }

        return searchset(patient, patientId, documents, CurrentUser.usernameOrSystem());
    }

    /**
     * Wraps the documents, registers the disclosure and writes the audit line.
     *
     * <p>Shared by both exports on purpose. The two differ in who runs them and how the contents
     * are chosen, and in nothing else — so if the bundle shape, the disclosure row or the audit
     * line ever diverged, one of the two would be the one that stopped being a record of what left.
     */
    private Map<String, Object> searchset(PatientView patient, UUID patientId,
                                          List<Map<String, Object>> documents, String releasedBy) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("resourceType", "Bundle");
        export.put("id", UUID.randomUUID().toString());
        export.put("type", "searchset");
        export.put("timestamp", Instant.now().toString());
        export.put("total", documents.size());
        export.put("entry", documents.stream()
                .map(document -> Map.of("resource", document))
                .toList());

        int resources = documents.stream().mapToInt(ExchangeService::countOf).sum();
        int bytes = objectMapper.writeValueAsBytes(export).length;
        Disclosure disclosure = disclosures.save(new Disclosure(null, patientId, patient.mrn(),
                HiType.HEALTH_DOCUMENT_RECORD, DisclosureKind.PATIENT_EXPORT, patient.mrn(),
                resources, bytes, releasedBy));

        // Loud on purpose. A whole-record export is the single most sensitive operation the
        // platform performs, and the audit line is what makes it visible rather than routine.
        audit.record("EHI_EXPORT", "Disclosure", disclosure.getId(),
                "%d document(s), %d resource(s) about %s exported by %s".formatted(
                        documents.size(), resources, patient.mrn(), releasedBy));
        return export;
    }

    /**
     * A patient's own record, exported by the patient.
     *
     * <p>The certification criterion's other half: view, download and <em>transmit</em>. What
     * distinguishes it from {@link #exportForPatient} is not the format — it is the same
     * {@code searchset} of the same documents — but who assembles it and how the contents are
     * chosen. There, an administrator names the encounters, orders and prescriptions to include;
     * here the platform lists everything the portal itself would show, because a patient asking for
     * their record means all of it.
     *
     * <p>The disclosure is recorded exactly as any other export is. A patient downloading their own
     * record is not a disclosure to anybody new, and that is precisely why it must be logged: the
     * register answers "what has left this platform, and to whom", and a register with one category
     * of departure missing is a register that cannot be relied on for the others.
     */
    @Transactional
    public Map<String, Object> exportForSelf(UUID patientId, String bearerToken) {
        PatientView patient = portal.me(bearerToken);
        if (!patient.id().equals(patientId)) {
            // Cannot happen: the id is the token's claim and the record is what the register
            // answered for that same token. Stated because the alternative to failing here is
            // handing one person a bundle assembled from another person's record.
            throw new IllegalStateException(
                    "The signed-in patient and the record read for them do not match");
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        for (var encounter : portal.myEncounters(bearerToken)) {
            documents.add(fhir.opConsultation(patient, encounter));
        }
        for (var order : portal.myReleasedLabOrders(bearerToken)) {
            documents.add(fhir.diagnosticReport(patient, order));
        }
        for (var prescription : portal.myPrescriptions(bearerToken)) {
            documents.add(fhir.prescription(patient, prescription));
        }
        // The portal account's own username, which is the patient's MRN: the register says who
        // released a record, and "the patient" would be true of every self-export ever made.
        return searchset(patient, patientId, documents, CurrentUser.usernameOrSystem());
    }

    /**
     * The staff view of the register: everything released about one patient, optionally bounded to
     * a period. Both bounds are optional, so the callers that predate them keep working unchanged.
     */
    @Transactional(readOnly = true)
    public List<InteropDtos.DisclosureResponse> disclosuresFor(UUID patientId, LocalDate from, LocalDate to) {
        List<Disclosure> register = register(patientId, from, to);
        Map<UUID, String> artefactIds = artefactIdsFor(register);
        return register.stream().map(row -> toResponse(row, artefactIds)).toList();
    }

    /**
     * The patient's own accounting of disclosures.
     *
     * <p>The same rows, read the same way, with the member of staff who released each one left
     * out — see {@code MyDisclosureResponse} for why. The patient is established from a signed
     * claim by the caller, never from a request parameter, so there is no id here to tamper with.
     */
    @Transactional(readOnly = true)
    public List<InteropDtos.MyDisclosureResponse> myDisclosures(UUID patientId, LocalDate from, LocalDate to) {
        List<Disclosure> register = register(patientId, from, to);
        Map<UUID, String> artefactIds = artefactIdsFor(register);
        return register.stream()
                .map(row -> new InteropDtos.MyDisclosureResponse(row.getId(),
                        artefactIdOf(row, artefactIds), row.getHiType(), row.getKind(),
                        row.getRecipient(), row.getResourceCount(), row.getByteCount(),
                        row.getReleasedAt()))
                .toList();
    }

    private List<Disclosure> register(UUID patientId, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return disclosures.findByPatientIdOrderByReleasedAtDesc(patientId);
        }
        // Half-open, and `to` is inclusive of the whole of that day: somebody asking what left
        // "up to the 14th" means the 14th included, and a register that quietly stopped at
        // midnight on the 13th would be read as nothing having happened.
        Instant fromInstant = from == null ? Instant.EPOCH : from.atStartOfDay(zone).toInstant();
        Instant toInstant = to == null
                ? Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS)
                : to.plusDays(1).atStartOfDay(zone).toInstant();
        return disclosures
                .findByPatientIdAndReleasedAtGreaterThanEqualAndReleasedAtLessThanOrderByReleasedAtDesc(
                        patientId, fromInstant, toInstant);
    }

    /** Resolves every row's public artefact id in one query rather than one per row. */
    private Map<UUID, String> artefactIdsFor(List<Disclosure> register) {
        return consents.artefactIdsById(register.stream()
                .map(Disclosure::getConsentId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
    }

    // ---- helpers -------------------------------------------------------------

    /** One record, its patient and the date a consent's covered period is compared against. */
    record Dated(UUID patientId, LocalDate date, EncounterView encounter, LabOrderView labOrder,
                 PrescriptionView prescription) {
    }

    private Dated dateOf(HiType hiType, UUID recordId, String bearerToken) {
        return switch (hiType) {
            case OP_CONSULTATION -> {
                EncounterView encounter = clinical.encounter(recordId, bearerToken);
                yield new Dated(encounter.patientId(), asDate(encounter.startedAt()), encounter,
                        null, null);
            }
            case DIAGNOSTIC_REPORT -> {
                LabOrderView order = clinical.labOrder(recordId, bearerToken);
                if (!"VERIFIED".equals(order.status())) {
                    // The whole point of the verification step is that an unreleased number does
                    // not become something another clinician treats from.
                    throw new ConflictException(("Laboratory order %s is %s. Only a verified, "
                            + "released report may be shared — a provisional result leaving the "
                            + "building is what verification exists to prevent.")
                            .formatted(recordId, order.status()));
                }
                yield new Dated(order.patientId(), asDate(order.orderedAt()), null, order, null);
            }
            case PRESCRIPTION -> {
                PrescriptionView prescription = clinical.prescription(recordId, bearerToken);
                yield new Dated(prescription.patientId(), asDate(prescription.issuedAt()), null,
                        null, prescription);
            }
            default -> throw new ConflictException(("This platform cannot yet build a %s bundle. "
                    + "The consent may cover it; there is nothing to send, and saying so is better "
                    + "than sending an empty document.").formatted(hiType));
        };
    }

    private Map<String, Object> build(HiType hiType, PatientView patient, Dated record,
                                      String bearerToken) {
        return switch (hiType) {
            case OP_CONSULTATION -> fhir.opConsultation(patient, record.encounter());
            case DIAGNOSTIC_REPORT -> fhir.diagnosticReport(patient, record.labOrder());
            case PRESCRIPTION -> fhir.prescription(patient, record.prescription());
            default -> throw new ConflictException(
                    "This platform cannot yet build a %s bundle.".formatted(hiType));
        };
    }

    private static LocalDate asDate(Instant instant) {
        return (instant == null ? Instant.now() : instant).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static int countOf(Map<String, Object> bundle) {
        Object entries = bundle.get("entry");
        return entries instanceof List<?> list ? list.size() : 0;
    }

    /**
     * A row's public artefact id, or null when there is no consent behind it — an export handed to
     * the patient themselves.
     *
     * <p>The null check is not defensive padding: {@code Map.of()} is what the batch lookup returns
     * when no row in the page has a consent, and it throws on a null key rather than answering
     * null. So a patient whose only disclosure was their own download got a 500 from the endpoint
     * built to reassure them. Found by the API suite, whose portal journey exports before it reads.
     */
    private static String artefactIdOf(Disclosure disclosure, Map<UUID, String> artefactIds) {
        return disclosure.getConsentId() == null ? null : artefactIds.get(disclosure.getConsentId());
    }

    private InteropDtos.DisclosureResponse toResponse(Disclosure disclosure, Map<UUID, String> artefactIds) {
        String artefactId = artefactIdOf(disclosure, artefactIds);
        return new InteropDtos.DisclosureResponse(disclosure.getId(), disclosure.getConsentId(),
                artefactId, disclosure.getPatientId(), disclosure.getPatientMrn(),
                disclosure.getHiType(), disclosure.getKind(), disclosure.getRecipient(),
                disclosure.getResourceCount(), disclosure.getByteCount(),
                disclosure.getReleasedBy(), disclosure.getReleasedAt());
    }
}
