package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ForbiddenException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.scheduling.domain.CareTeamMember;
import com.hms.scheduling.domain.Encounter;
import com.hms.scheduling.repo.CareTeamRepository;
import com.hms.scheduling.domain.PatientCareGrant;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.repo.PatientCareGrantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether this clinician may read this chart — and how they get in when they may not.
 *
 * <p>Until this existed, {@code Roles.CHART_READ} was the whole answer: every doctor and every
 * nurse could read every encounter on the platform. That is a role gate, and a role gate cannot
 * express "is this your patient", so nothing did. The narrowing and break-glass are the same
 * mechanism seen from two sides and they ship together: an override on top of access somebody
 * already has is theatre with an audit line attached.
 *
 * <p><strong>Why this is a service method and not SpEL.</strong> A {@code @PreAuthorize} expression
 * runs before the method body and cannot see the encounter's care team, so {@code CHART_READ} stays
 * exactly as it was — it remains the role gate, deciding who may read a clinical record at all —
 * and this decides whose. Both, in that order.
 *
 * <p><strong>Who is narrowed.</strong> Doctors and nurses. Administrators are not: narrowing the
 * account that repairs the platform is a different decision, and the honest statement (which the
 * README makes) is that this narrows clinicians. Pathologists are not either, and neither are the
 * other service lines — reporting a specimen, dispensing a drug and running a blood count are
 * inherently cross-patient jobs, and a care-relationship model does not describe them.
 *
 * <p><strong>Reading is narrowed; providing care enrols you.</strong> This asymmetry is the design,
 * and the alternative was worse. A nurse appears in {@code encounters.clinician_id} nowhere, so a
 * symmetric rule would have every nurse recording a break-glass reason for every patient they were
 * sent to obs — and a control everybody trips over every hour is a control everybody learns to
 * click through. So the acts of care (obs, a note, a diagnosis, an order set, a care plan) go
 * through the role gate as they always did and put the person on the team by doing so, while
 * <em>reading</em> a chart requires already being on it.
 *
 * <p>That targets the risk that actually exists. "Who has been looking at my record" is a question
 * about browsing, and browsing is what this stops. The other direction — falsifying a clinical
 * record to gain a read — is a far graver act than the read it buys, is permanently attributable to
 * the person who did it, and is exactly what the audit trail is for.
 */
@Service
public class CareTeamGuard {

    private static final Logger log = LoggerFactory.getLogger(CareTeamGuard.class);

    /** The refusal, in words that say what to do about it. */
    private static final String NOT_ON_THE_TEAM =
            "You are not on this encounter's care team, so this is not your patient's chart to read."
                    + " If you need it — cover, a handover, an emergency — record a reason and it"
                    + " will open. That is logged and reviewed.";

    private final CareTeamRepository careTeam;
    private final EncounterRepository encounters;
    private final PatientCareGrantRepository grants;
    private final AuditService audit;
    private final Duration breakGlassTtl;
    private final int reasonMinLength;

    public CareTeamGuard(CareTeamRepository careTeam, EncounterRepository encounters,
                         PatientCareGrantRepository grants, AuditService audit,
                         @Value("${hms.care-team.break-glass-ttl:PT12H}") Duration breakGlassTtl,
                         @Value("${hms.care-team.reason-min-length:20}") int reasonMinLength) {
        this.careTeam = careTeam;
        this.encounters = encounters;
        this.grants = grants;
        this.audit = audit;
        this.breakGlassTtl = breakGlassTtl;
        this.reasonMinLength = reasonMinLength;
    }

    /**
     * Refuses the call unless the caller may read this chart. Applied to reads <em>and</em> writes:
     * signing a note on an encounter you are not on is the same refusal as reading it.
     *
     * @throws ForbiddenException with the reason, rather than a 404. The deliberate opposite of
     *         the portal's rule, where "not yours" would confirm a guessed patient id is real. Here
     *         the caller is a clinician who can already list patients, so there is nothing to
     *         enumerate — and an answer that says what to do next is worth more than one that
     *         pretends the encounter does not exist.
     */
    @Transactional(readOnly = true)
    public void requireChartAccess(UUID encounterId) {
        if (!isNarrowed()) {
            return;
        }
        UUID caller = CurrentUser.id().orElseThrow(() -> new ForbiddenException(NOT_ON_THE_TEAM));
        if (careTeam.isCurrentMember(encounterId, caller, Instant.now())) {
            return;
        }
        // Logged, not audited. A clinician being told "not your patient" is the control working, and
        // an audit row for every refused read would bury the break-glass rows that matter.
        log.info("Chart access refused: user {} is not on encounter {}'s care team", caller, encounterId);
        throw new ForbiddenException(NOT_ON_THE_TEAM);
    }

    /**
     * Puts the caller on the team because they are about to record something clinical on this
     * encounter. Called on the write paths, which keep the role gate they always had.
     *
     * <p>Not a permission check: whoever reached here already holds {@code CLINICAL_WRITE}, and
     * what this records is that they took part. It is what makes a nurse's day work without a
     * reason box in front of every set of observations.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enrolOnContact(UUID encounterId) {
        if (!isNarrowed()) {
            return;
        }
        CurrentUser.id()
                .filter(caller -> !careTeam.existsByEncounterIdAndUserId(encounterId, caller))
                .ifPresent(caller -> careTeam.save(CareTeamMember.providedCare(encounterId, caller)));
    }

    /**
     * Enrols the encounter's own clinician when it opens.
     *
     * <p>This is the half that makes the narrowing shippable rather than an outage: the treating
     * doctor's experience is byte-for-byte what it was. Idempotent, because an encounter can be
     * opened from an appointment whose clinician is already on another team member's row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enrolOnOpening(UUID encounterId, UUID clinicianId) {
        if (clinicianId != null && !careTeam.existsByEncounterIdAndUserId(encounterId, clinicianId)) {
            careTeam.save(CareTeamMember.treatingClinician(encounterId, clinicianId));
        }
        // And whoever opened it, when that is somebody else — the nurse at the desk starting a
        // walk-in for a doctor. Without this, `open` would write the encounter and then refuse the
        // caller the record it returns, which is the kind of trap that gets discovered in a clinic.
        CurrentUser.id()
                .filter(opener -> !opener.equals(clinicianId))
                .filter(opener -> !careTeam.existsByEncounterIdAndUserId(encounterId, opener))
                .ifPresent(opener -> careTeam.save(CareTeamMember.opener(encounterId, opener)));
    }

    /**
     * Break-glass: joins the caller to the team, with a reason, for one shift.
     *
     * <p>The reason is stored on the row and <strong>not</strong> in the audit record's
     * {@code detail}. That is the platform's own rule — audit detail must never carry clinical free
     * text — and "unresponsive, query sepsis" is a clinical observation. So the clinical text stays
     * in the clinical schema and the audit trail carries the action, the encounter and the fact that
     * somebody broke the glass. The audit report is where it surfaces, filterable by that action.
     */
    @Transactional
    public CareTeamMember breakGlass(UUID encounterId, String rawReason) {
        Encounter encounter = encounters.findById(encounterId)
                .orElseThrow(() -> NotFoundException.of("Encounter", encounterId));
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < reasonMinLength) {
            // A length floor is a blunt instrument and it is here for a blunt reason: "cover" and
            // "emergency" are what a free-text box collects when it does not ask for a sentence,
            // and a reason nobody can act on is the same as no reason at all.
            throw new BadRequestException("Say why you need this chart, in a sentence — at least "
                    + reasonMinLength + " characters. It is read by the people who review this.");
        }
        UUID caller = CurrentUser.id().orElseThrow(() ->
                new ForbiddenException("This session is not linked to a user account."));

        if (careTeam.isCurrentMember(encounterId, caller, Instant.now())) {
            throw new BadRequestException("You are already on this encounter's care team.");
        }
        // Replaces a lapsed membership rather than colliding with the unique constraint: cover that
        // expired last week and cover needed again tonight are two different decisions, and the
        // second should record its own reason.
        careTeam.findByEncounterIdOrderByJoinedAtDesc(encounterId).stream()
                .filter(member -> member.getUserId().equals(caller))
                .forEach(careTeam::delete);

        CareTeamMember joined = careTeam.save(CareTeamMember.breakGlass(
                encounterId, caller, reason, Instant.now().plus(breakGlassTtl)));

        audit.record("CHART_BREAK_GLASS", "Encounter", encounterId,
                "care team joined for " + breakGlassTtl + " about " + encounter.getPatientMrn());
        log.warn("Break-glass: user {} joined encounter {}'s care team", caller, encounterId);
        return joined;
    }

    @Transactional(readOnly = true)
    public List<CareTeamMember> team(UUID encounterId) {
        return careTeam.findByEncounterIdOrderByJoinedAtDesc(encounterId);
    }

    /** Whether the caller may read this chart, without refusing. For rendering a screen. */
    @Transactional(readOnly = true)
    public boolean mayReadChart(UUID encounterId) {
        if (!isNarrowed()) {
            return true;
        }
        return CurrentUser.id()
                .map(caller -> careTeam.isCurrentMember(encounterId, caller, Instant.now()))
                .orElse(false);
    }

    // ---- the patient-level question -------------------------------------------

    /**
     * Whether a clinician may see this patient's clinical record at all.
     *
     * <p>Asked by laboratory-service and pharmacy-service before they show a doctor or a nurse
     * somebody's results or prescriptions. The chart narrowing answers this for an encounter; those
     * services hold records that belong to a <em>patient</em>, and a walk-in blood test has no
     * encounter behind it to ask about.
     *
     * <p>Two ways in, and they are the ordinary path and the exception. Being on the care team of
     * any of the patient's encounters is the first: looking after somebody is what entitles you to
     * the rest of their record, and deriving it from the team rather than storing it again means
     * there is no second table to disagree with the first. A live break-glass grant is the second.
     *
     * <p>Answered for the caller named in the request rather than for the bearer of the token,
     * because these callers are services asking on behalf of a clinician — and the token they
     * forward is that clinician's own, so the two are the same person. Taking the id from the token
     * is what stops a service asking about somebody else.
     */
    @Transactional(readOnly = true)
    public boolean mayReadPatientRecord(UUID patientId) {
        if (!isNarrowed()) {
            return true;
        }
        Instant now = Instant.now();
        return CurrentUser.id()
                .map(caller -> careTeam.isOnAnyEncounterFor(patientId, caller, now)
                        || grants.hasLiveGrant(patientId, caller, now))
                .orElse(false);
    }

    /**
     * Break-glass at the patient level: a time-boxed relationship with everything about them.
     *
     * <p>Wider than the encounter version and deliberately so — it is what a covering clinician
     * needs when there is no encounter of theirs to break into, and it opens the laboratory and the
     * pharmacy along with the chart. Same reason floor, same expiry, same audit action, so the
     * review that counts break-glass events counts these too.
     */
    @Transactional
    public PatientCareGrant breakGlassForPatient(UUID patientId, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < reasonMinLength) {
            throw new BadRequestException("Say why you need this patient's record, in a sentence — "
                    + "at least " + reasonMinLength + " characters. It is read by the people who "
                    + "review this.");
        }
        UUID caller = CurrentUser.id().orElseThrow(() ->
                new ForbiddenException("This session is not linked to a user account."));

        Instant now = Instant.now();
        if (grants.hasLiveGrant(patientId, caller, now)) {
            throw new BadRequestException(
                    "You already have access to this patient's record, granted earlier today.");
        }

        PatientCareGrant grant = grants.save(
                new PatientCareGrant(patientId, caller, reason, now.plus(breakGlassTtl)));

        // The same action as the chart's, so one filter finds every break-glass on the platform.
        // The reason stays on the row and out of the audit detail: it is clinical free text, and
        // audit detail on this platform never carries any.
        audit.record("CHART_BREAK_GLASS", "Patient", patientId,
                "patient record opened for " + breakGlassTtl);
        log.warn("Break-glass: user {} opened patient {}'s record", caller, patientId);
        return grant;
    }

    /** Every exception granted on this patient, for the card and for the review. */
    @Transactional(readOnly = true)
    public List<PatientCareGrant> grantsFor(UUID patientId) {
        return grants.findByPatientIdOrderByGrantedAtDesc(patientId);
    }

    /**
     * Whether the narrowing applies to this caller at all.
     *
     * <p>An administrator or a pathologist reaching a chart is still gated by {@code CHART_READ}
     * and is not gated by membership. Expressed as "is a clinician and is not an administrator"
     * rather than "is not an administrator", so a role added later is outside the narrowing until
     * somebody decides otherwise — the safer direction for a check whose failure mode is locking a
     * new role out of every chart on the platform.
     */
    private static boolean isNarrowed() {
        return !CurrentUser.hasAnyRole(Roles.ADMIN)
                && CurrentUser.hasAnyRole(Roles.DOCTOR, Roles.NURSE);
    }
}
