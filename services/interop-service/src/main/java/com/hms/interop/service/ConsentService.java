package com.hms.interop.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.interop.domain.ConsentArtefact;
import com.hms.interop.domain.InteropEnums.ConsentStatus;
import com.hms.interop.domain.InteropEnums.HiType;
import com.hms.interop.repo.ConsentRepository;
import com.hms.interop.web.dto.InteropDtos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent, and the one method that decides whether anything may leave.
 *
 * <p>{@link #authorise} is the whole module's reason for existing, and three things about it are
 * deliberate:
 *
 * <ul>
 *   <li><strong>There is no bypass.</strong> No flag, no "administrative override", no role that
 *       skips it. A break-the-glass emergency access is a real requirement and it is a *purpose
 *       code on a consent*, recorded and loudly audited, rather than a way around the check.</li>
 *   <li><strong>It answers with the artefact or throws.</strong> Not a boolean. A caller that
 *       received {@code false} would have to decide what to say, and the useful sentence — which
 *       of the four conditions failed — is only available here.</li>
 *   <li><strong>Expiry is compared against the clock, every time.</strong> The stored EXPIRED
 *       status is housekeeping so a list query is cheap; enforcement never depends on a job
 *       having run, because a platform where a missed cron job is a disclosure is not a platform
 *       with consent enforcement.</li>
 * </ul>
 */
@Service
public class ConsentService {

    private final ConsentRepository consents;
    private final AuditService audit;
    private final int maxConsentDays;

    public ConsentService(ConsentRepository consents, AuditService audit,
                          @Value("${hms.interop.max-consent-days:180}") int maxConsentDays) {
        this.consents = consents;
        this.audit = audit;
        this.maxConsentDays = maxConsentDays;
    }

    // ---- the gate ------------------------------------------------------------

    /**
     * The consent that authorises disclosing this kind of information about this date, or a
     * refusal saying which condition failed.
     *
     * <p>Each refusal names consent explicitly, because the person reading it has to be able to
     * tell "we are not allowed to send this" from "something is broken" — and because a message
     * that said only "forbidden" would teach whoever sees it to look for a way round.
     */
    @Transactional(readOnly = true)
    public ConsentArtefact authorise(String artefactId, HiType hiType, LocalDate recordDate) {
        ConsentArtefact consent = authorise(artefactId, hiType);
        assertCovers(consent, recordDate);
        return consent;
    }

    /**
     * Everything that can be decided without touching the record: is this consent granted, live,
     * and for this kind of information.
     *
     * <p>Separate from the date check so a caller can run it <em>first</em>. Reading a chart and
     * then discovering the consent was revoked would mean a refused request had already pulled the
     * record into this service, and whether that counts as a disclosure is not an argument worth
     * having when the order can simply be right.
     */
    @Transactional(readOnly = true)
    public ConsentArtefact authorise(String artefactId, HiType hiType) {
        ConsentArtefact consent = consents.findByArtefactId(artefactId)
                .orElseThrow(() -> new NotFoundException(
                        "No consent artefact '%s'. Nothing can be shared without one."
                                .formatted(artefactId)));

        if (consent.getStatus() == ConsentStatus.REVOKED) {
            throw new ConflictException(("Consent %s was revoked (%s). The patient has withdrawn "
                    + "permission and nothing further may be shared under it.")
                    .formatted(artefactId, consent.getRevokedReason()));
        }
        if (consent.getStatus() == ConsentStatus.DENIED) {
            throw new ConflictException(
                    "Consent %s was refused by the patient.".formatted(artefactId));
        }
        if (consent.getStatus() == ConsentStatus.REQUESTED) {
            throw new ConflictException(("Consent %s has been requested and not yet granted. A "
                    + "pending request is not permission.").formatted(artefactId));
        }
        if (consent.hasLapsed(Instant.now())) {
            throw new ConflictException(("Consent %s expired on %s. A fresh consent has to be "
                    + "requested; an expired one cannot be extended.")
                    .formatted(artefactId, consent.getExpiresAt()));
        }
        if (!consent.getHiTypes().contains(hiType)) {
            throw new ConflictException(("Consent %s does not cover %s. It covers %s, and consent "
                    + "for one kind of record is not consent for another.")
                    .formatted(artefactId, readable(hiType),
                            consent.getHiTypes().stream()
                                    .sorted(Comparator.comparing(Enum::name))
                                    .map(ConsentService::readable)
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("nothing")));
        }
        return consent;
    }

    /**
     * Whether the consent covers a record of this date.
     *
     * <p>The fourth condition, and the one people get wrong: "you may see my records from last
     * year" is a different sentence from "this permission lasts a year", and the refusal says so
     * because somebody reading it will otherwise try to fix the wrong date.
     */
    @Transactional(readOnly = true)
    public void assertCovers(ConsentArtefact consent, LocalDate recordDate) {
        if (recordDate.isBefore(consent.getCoversFrom())
                || recordDate.isAfter(consent.getCoversTo())) {
            throw new ConflictException(("Consent %s covers records dated %s to %s, and this one "
                    + "is dated %s. The period a consent covers is not the same as how long the "
                    + "consent lasts.").formatted(consent.getArtefactId(), consent.getCoversFrom(),
                    consent.getCoversTo(), recordDate));
        }
    }

    /** Whether a consent would authorise a disclosure right now, for a screen to render. */
    static boolean isLive(ConsentArtefact consent, Instant now) {
        return consent.getStatus() == ConsentStatus.GRANTED && !consent.hasLapsed(now);
    }

    // ---- the artefacts -------------------------------------------------------

    @Transactional
    public InteropDtos.ConsentResponse request(InteropDtos.RequestConsentRequest request) {
        if (request.coversTo().isBefore(request.coversFrom())) {
            throw new BadRequestException(
                    "A consent cannot cover a period that ends before it starts.");
        }
        long days = Duration.between(Instant.now(), request.expiresAt()).toDays();
        if (days > maxConsentDays) {
            throw new BadRequestException(("A consent may last at most %d days and this one would "
                    + "last %d. Permission to read somebody's medical record is not the kind of "
                    + "thing to grant open-endedly; request a fresh one when it lapses.")
                    .formatted(maxConsentDays, days));
        }

        String artefactId = request.artefactId() == null || request.artefactId().isBlank()
                // Ours when no consent manager has issued one. Prefixed so nobody mistakes a
                // locally minted id for an ABDM artefact in a log or a support conversation.
                ? "LOCAL-" + UUID.randomUUID().toString().substring(0, 18).toUpperCase(Locale.ROOT)
                : request.artefactId().trim();

        ConsentArtefact consent = new ConsentArtefact(artefactId, request.patientId(),
                request.patientMrn().trim(), request.requester().trim(), request.requesterId(),
                request.purposeCode(), request.purposeText(), request.coversFrom(),
                request.coversTo(), request.expiresAt(), request.hiTypes());
        ConsentArtefact saved;
        try {
            saved = consents.saveAndFlush(consent);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(("A consent artefact '%s' already exists. Two rows for one "
                    + "artefact is two answers to whether data may move, and the wrong one will be "
                    + "the one somebody reads.").formatted(artefactId));
        }
        audit.record("CONSENT_REQUESTED", "ConsentArtefact", saved.getId(),
                "%s for %s by %s".formatted(artefactId, saved.getPatientMrn(),
                        saved.getRequester()));
        return toResponse(saved);
    }

    @Transactional
    public InteropDtos.ConsentResponse grant(String artefactId,
                                             InteropDtos.GrantConsentRequest request) {
        ConsentArtefact consent = require(artefactId);
        if (consent.getStatus() != ConsentStatus.REQUESTED) {
            throw new ConflictException(("Consent %s is %s. A consent is granted once, in answer "
                    + "to a request; re-granting a revoked one would erase the revocation.")
                    .formatted(artefactId, consent.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        if (consent.hasLapsed(Instant.now())) {
            throw new ConflictException(("Consent %s expired before it was granted. Request a "
                    + "fresh one.").formatted(artefactId));
        }
        consent.grant(request == null ? null : request.signature());
        audit.record("CONSENT_GRANTED", "ConsentArtefact", consent.getId(),
                "%s until %s".formatted(artefactId, consent.getExpiresAt()));
        return toResponse(consent);
    }

    @Transactional
    public InteropDtos.ConsentResponse deny(String artefactId) {
        ConsentArtefact consent = require(artefactId);
        if (consent.getStatus() != ConsentStatus.REQUESTED) {
            throw new ConflictException("Consent %s has already been answered.".formatted(artefactId));
        }
        consent.deny();
        audit.record("CONSENT_DENIED", "ConsentArtefact", consent.getId(), artefactId);
        return toResponse(consent);
    }

    /**
     * Withdraws a consent.
     *
     * <p>Allowed from GRANTED and from EXPIRED, which looks odd and is not: a patient who says
     * "stop using that" about a consent that lapsed last week is entitled to have the withdrawal
     * on the record, and refusing on a technicality would leave the register saying the permission
     * merely ran out.
     */
    @Transactional
    public InteropDtos.ConsentResponse revoke(String artefactId,
                                              InteropDtos.RevokeConsentRequest request) {
        ConsentArtefact consent = require(artefactId);
        if (consent.getStatus() == ConsentStatus.REVOKED) {
            throw new ConflictException("Consent %s is already revoked.".formatted(artefactId));
        }
        if (consent.getStatus() == ConsentStatus.DENIED) {
            throw new ConflictException(("Consent %s was never granted, so there is nothing to "
                    + "revoke.").formatted(artefactId));
        }
        consent.revoke(request.reason().trim());
        audit.record("CONSENT_REVOKED", "ConsentArtefact", consent.getId(),
                "%s: %s".formatted(artefactId, consent.getRevokedReason()));
        return toResponse(consent);
    }

    @Transactional(readOnly = true)
    public InteropDtos.ConsentResponse read(String artefactId) {
        return toResponse(require(artefactId));
    }

    @Transactional(readOnly = true)
    public List<InteropDtos.ConsentResponse> forPatient(UUID patientId) {
        return consents.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InteropDtos.ConsentResponse> list(boolean includeFinished) {
        List<ConsentStatus> statuses = includeFinished
                ? List.of(ConsentStatus.values())
                : List.of(ConsentStatus.REQUESTED, ConsentStatus.GRANTED);
        return consents.findByStatusInOrderByCreatedAtDesc(statuses).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Marks lapsed grants expired.
     *
     * <p>Exposed rather than scheduled: a deployment can call it from whatever runs its cron, and
     * nothing depends on it having run — {@link #authorise} compares against the clock. So this is
     * tidying, and it is written down as tidying so nobody mistakes it for the control.
     */
    @Transactional
    public int expireLapsed() {
        int expired = consents.markLapsed(Instant.now());
        if (expired > 0) {
            audit.record("CONSENTS_EXPIRED", "ConsentArtefact", null,
                    expired + " lapsed consent(s) marked expired");
        }
        return expired;
    }

    ConsentArtefact require(String artefactId) {
        return consents.findByArtefactId(artefactId)
                .orElseThrow(() -> new NotFoundException(
                        "No consent artefact '%s'".formatted(artefactId)));
    }

    Optional<ConsentArtefact> find(String artefactId) {
        return consents.findByArtefactId(artefactId);
    }

    /** By primary key, for a disclosure row that stores the id rather than the artefact id. */
    @Transactional(readOnly = true)
    public Optional<ConsentArtefact> findById(UUID id) {
        return consents.findById(id);
    }

    InteropDtos.ConsentResponse toResponse(ConsentArtefact consent) {
        return new InteropDtos.ConsentResponse(consent.getId(), consent.getArtefactId(),
                consent.getPatientId(), consent.getPatientMrn(), consent.getRequester(),
                consent.getRequesterId(), consent.getPurposeCode(), consent.getPurposeText(),
                consent.getStatus(), isLive(consent, Instant.now()),
                consent.getHiTypes().stream().sorted(Comparator.comparing(Enum::name)).toList(),
                consent.getCoversFrom(), consent.getCoversTo(), consent.getExpiresAt(),
                consent.getGrantedAt(), consent.getDeniedAt(), consent.getRevokedAt(),
                consent.getRevokedReason(), consent.getSignature() != null);
    }

    /** "diagnostic report", not "DIAGNOSTIC_REPORT" — the refusal is read by people. */
    private static String readable(HiType hiType) {
        return hiType.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
