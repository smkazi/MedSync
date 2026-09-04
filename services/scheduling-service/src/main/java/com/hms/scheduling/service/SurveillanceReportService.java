package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.csv.CsvWriter;
import com.hms.common.error.BadRequestException;
import com.hms.common.security.CurrentUser;
import com.hms.scheduling.client.DisclosureRegisterClient;
import com.hms.scheduling.domain.NotifiableCondition;
import com.hms.scheduling.repo.DiagnosisRepository;
import com.hms.scheduling.repo.NotifiableConditionRepository;
import com.hms.scheduling.web.dto.SurveillanceDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notifiable-disease return: how many cases of each reportable condition, over a period.
 *
 * <p><strong>An aggregate that cannot leak an identifier, because it never selects one.</strong> The
 * query groups by code in the database and returns two columns, and the projection it returns
 * through has nowhere to put a patient id. That is a stronger guarantee than a mapper that leaves
 * fields out: a field added to a DTO later is a disclosure, a column added to that select is a
 * compile error.
 *
 * <p><strong>And no condition × department cross-tab</strong>, deliberately, however obviously
 * useful one would be on a screen. A rare condition against a small department re-identifies by
 * arithmetic — one case of rabies in a four-bed unit names a patient to anybody who works there —
 * and the whole point of an aggregate is that it does not do that.
 *
 * <p>Shaped like {@code AuditReportService} one service along: one {@link Filters} record used by
 * the screen and the CSV so they cannot drift, a filename carrying the period, the {@code TRUNCATED}
 * sentinel when a cap bites, and {@code com.hms.common.csv.CsvWriter} reused rather than a string
 * join — an export a person opens in a spreadsheet needs the formula-injection neutralising that
 * writer already does.
 *
 * <p>The period is resolved in the hospital's zone, from the {@code HMS_ZONE} chain. That is why
 * scheduling had to join that chain before this existed: a notifiable week running Monday-to-Sunday
 * UTC in an IST hospital puts five and a half hours of every Sunday into the next week's return.
 */
@Service
public class SurveillanceReportService {

    /** Column order for the export. Fixed, because somebody will build a spreadsheet on it. */
    private static final List<String> HEADER =
            List.of("icd10Code", "condition", "cases", "notifyWithinHours");

    /**
     * Column order for the line list.
     *
     * <p>An MRN and not a patient id: the authority receiving this has to be able to ask the
     * hospital about a case, and an internal UUID is a number nobody outside can use. The id is on
     * the JSON so a screen can link to a chart, and off the file so the file carries no identifier
     * the recipient has no use for.
     */
    private static final List<String> LINE_LIST_HEADER =
            List.of("patientMrn", "icd10Code", "condition", "diagnosedOn", "notifyWithinHours");

    /** How far back an unbounded report reaches, matching the audit report's window. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final DiagnosisRepository diagnoses;
    private final NotifiableConditionRepository conditions;
    private final DisclosureRegisterClient register;
    private final AuditService audit;
    private final ZoneId zone;
    private final int smallCellThreshold;
    private final String authority;

    public SurveillanceReportService(DiagnosisRepository diagnoses,
                                     NotifiableConditionRepository conditions,
                                     DisclosureRegisterClient register, AuditService audit,
                                     @Value("${hms.scheduling.zone:Asia/Kolkata}") ZoneId zone,
                                     @Value("${hms.surveillance.small-cell-threshold:0}")
                                     int smallCellThreshold,
                                     @Value("${hms.surveillance.authority:District public health authority}")
                                     String authority) {
        this.diagnoses = diagnoses;
        this.conditions = conditions;
        this.register = register;
        this.audit = audit;
        this.zone = zone;
        this.smallCellThreshold = smallCellThreshold;
        this.authority = authority;
    }

    /** What a caller asked for. A record so the screen and the export cannot drift apart. */
    public record Filters(LocalDate from, LocalDate to) {
    }

    /** The configured list: which codes are reportable, and how fast. */
    @Transactional(readOnly = true)
    public List<SurveillanceDtos.NotifiableConditionResponse> configured() {
        return conditions.findByActiveTrueOrderByIcd10CodeAsc().stream()
                .map(condition -> new SurveillanceDtos.NotifiableConditionResponse(
                        condition.getIcd10Code(), condition.getConditionName(),
                        condition.getNotifyWithinHours(), condition.isActive()))
                .toList();
    }

    /**
     * The counts.
     *
     * <p>Every configured condition appears, including the ones with no cases. A report that
     * omitted the zeroes would be a report where "no cholera this fortnight" and "cholera is not on
     * our list" render identically, and those are very different facts about a district.
     */
    @Transactional(readOnly = true)
    public SurveillanceDtos.NotifiableReportResponse report(Filters filters) {
        LocalDate from = filters.from() == null
                ? today().minusDays(DEFAULT_WINDOW_DAYS) : filters.from();
        LocalDate to = filters.to() == null ? today() : filters.to();
        if (to.isBefore(from)) {
            throw new BadRequestException(("A period from %s to %s runs backwards.")
                    .formatted(from, to));
        }

        List<NotifiableCondition> list = conditions.findByActiveTrueOrderByIcd10CodeAsc();
        // The counts, and the audit row, are recorded even when the list is empty -- an empty
        // configured list is a configuration problem and the report should say so by having no
        // rows, rather than by failing.
        Map<String, Long> counts = new HashMap<>();
        if (!list.isEmpty()) {
            List<String> codes = list.stream().map(NotifiableCondition::getIcd10Code).toList();
            // Half-open and exclusive of the day after, so `to = today` includes all of today --
            // the same boundary the audit report uses, resolved in the same zone.
            for (DiagnosisRepository.NotifiableCount row : diagnoses.notifiableCounts(codes,
                    startOf(from), startOf(to.plusDays(1)))) {
                counts.put(row.getIcd10Code(), row.getCases());
            }
        }

        // The period and the total, and no code and no count: which conditions a district has is
        // itself sensitive in a small one, and an audit detail is not the place for it.
        audit.record("SURVEILLANCE_REPORT", "Diagnosis", null,
                "%s..%s, %d condition(s) configured, for %s".formatted(from, to, list.size(),
                        CurrentUser.usernameOrSystem()));

        List<SurveillanceDtos.NotifiableCountResponse> rows = new ArrayList<>();
        long total = 0;
        boolean suppressed = false;
        for (NotifiableCondition condition : list) {
            long cases = counts.getOrDefault(condition.getIcd10Code(), 0L);
            total += cases;
            boolean hidden = cases > 0 && smallCellThreshold > 0 && cases < smallCellThreshold;
            suppressed = suppressed || hidden;
            rows.add(new SurveillanceDtos.NotifiableCountResponse(condition.getIcd10Code(),
                    condition.getConditionName(), hidden ? null : cases,
                    condition.getNotifyWithinHours(), hidden));
        }
        return new SurveillanceDtos.NotifiableReportResponse(from, to, zone.getId(), rows, total,
                smallCellThreshold, suppressed, Instant.now());
    }

    /**
     * The same report as CSV.
     *
     * <p>Not capped, unlike the audit export, and the reason is a property of the data rather than
     * an oversight: this report has exactly as many rows as there are configured conditions —
     * eighteen on a seeded deployment — because it is a group-by over a fixed list. There is no
     * input that makes it large. The {@code TRUNCATED} sentinel appears for the one thing that
     * <em>can</em> shorten it, which is small-cell suppression.
     */
    @Transactional(readOnly = true)
    public String toCsv(Filters filters) {
        SurveillanceDtos.NotifiableReportResponse report = report(filters);
        CsvWriter csv = new CsvWriter(HEADER);
        for (SurveillanceDtos.NotifiableCountResponse row : report.conditions()) {
            csv.row(List.of(row.icd10Code(), row.conditionName(),
                    row.cases() == null ? "SUPPRESSED" : String.valueOf(row.cases()),
                    String.valueOf(row.notifyWithinHours())));
        }
        if (report.suppressed()) {
            csv.row(List.of("TRUNCATED", "",
                    "one or more counts below the small-cell threshold of " + smallCellThreshold
                            + " are suppressed", ""));
        }
        return csv.toCsv();
    }

    // ---- the line list -------------------------------------------------------

    /**
     * The names behind the counts, without registering a disclosure.
     *
     * <p>A preview, and the distinction it rests on is the one the platform draws everywhere else:
     * reading a record inside the hospital is <strong>audited</strong>, and handing one to somebody
     * outside is <strong>registered</strong>. An administrator looking at who is on this fortnight's
     * return has not notified anybody, so registering a disclosure for it would fill the register —
     * the thing a patient reads to find out who has seen their record — with rows about a screen
     * being opened. {@link #lineListCsv} is the act that notifies, and that one registers.
     *
     * <p>Audited under its own action, with the count and no code and no patient. This is the one
     * read in the module that names people, so it is exactly the read somebody will later ask "who
     * has been opening this?" about.
     */
    @Transactional(readOnly = true)
    public SurveillanceDtos.NotifiableLineListResponse lineList(Filters filters) {
        Window window = window(filters);
        List<SurveillanceDtos.NotifiableCaseResponse> cases = cases(window);
        audit.record("PUBLIC_HEALTH_LINE_LIST_VIEWED", "Diagnosis", null,
                "%s..%s, %d case(s), for %s".formatted(window.from(), window.to(), cases.size(),
                        CurrentUser.usernameOrSystem()));
        return new SurveillanceDtos.NotifiableLineListResponse(window.from(), window.to(),
                zone.getId(), cases, distinctPatients(cases), authority, false,
                "Viewed, not notified. Downloading the CSV is what records a disclosure against "
                        + "each patient named here.", Instant.now());
    }

    /**
     * The line list as the file that goes to the authority — <strong>registered first</strong>.
     *
     * <p>The order is the whole design. The disclosure register is written before a single byte of
     * CSV is produced, and if interop-service cannot be reached the client throws and no file
     * exists: the caller gets a 503 and tries again. The residual is real and is the right way
     * round — these are two writes and not one transaction, so a crash between them leaves a
     * disclosure row for a file nobody received. An over-recorded disclosure is a question somebody
     * can answer; an unrecorded one is a notification that happened and cannot be found, which is
     * the failure the register exists to prevent.
     *
     * <p>One disclosure row per <em>patient</em>, not one per run. {@code disclosures.patient_id} is
     * NOT NULL and its index is what answers a patient asking who has seen their record: a
     * run-level row would need a fabricated patient id and would be invisible to every patient on
     * the list.
     */
    @Transactional(readOnly = true)
    public Notification lineListCsv(Filters filters) {
        Window window = window(filters);
        List<SurveillanceDtos.NotifiableCaseResponse> cases = cases(window);
        if (cases.isEmpty()) {
            // Nothing to notify, so nothing to register. A disclosure row for an empty file would
            // be a record of a notification that named nobody.
            audit.record("PUBLIC_HEALTH_LINE_LIST_EMPTY", "Diagnosis", null,
                    "%s..%s, no cases, for %s".formatted(window.from(), window.to(),
                            CurrentUser.usernameOrSystem()));
            return new Notification(csv(cases), 0, filename(window));
        }

        // One subject per distinct patient, with how many of their rows the file carries.
        Map<UUID, DisclosureRegisterClient.Subject> subjects = new LinkedHashMap<>();
        for (SurveillanceDtos.NotifiableCaseResponse row : cases) {
            subjects.merge(row.patientId(),
                    new DisclosureRegisterClient.Subject(row.patientId(), row.patientMrn(), 1),
                    (existing, one) -> new DisclosureRegisterClient.Subject(existing.patientId(),
                            existing.patientMrn(), existing.rowCount() + 1));
        }

        int registered = register.record(authority, List.copyOf(subjects.values()));

        audit.record("PUBLIC_HEALTH_LINE_LIST_RELEASED", "Diagnosis", null,
                "%s..%s, %d patient(s) to %s, by %s".formatted(window.from(), window.to(),
                        registered, authority, CurrentUser.usernameOrSystem()));
        return new Notification(csv(cases), registered, filename(window));
    }

    /** A produced notification: the file, how many patients it named, and what to call it. */
    public record Notification(String csv, int patients, String filename) {
    }

    private List<SurveillanceDtos.NotifiableCaseResponse> cases(Window window) {
        List<NotifiableCondition> list = conditions.findByActiveTrueOrderByIcd10CodeAsc();
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, NotifiableCondition> byCode = new HashMap<>();
        list.forEach(condition -> byCode.put(condition.getIcd10Code(), condition));
        return diagnoses.notifiableCases(byCode.keySet(), window.fromInstant(), window.toInstant())
                .stream()
                .map(row -> {
                    NotifiableCondition condition = byCode.get(row.getIcd10Code());
                    return new SurveillanceDtos.NotifiableCaseResponse(row.getPatientId(),
                            row.getPatientMrn(), row.getIcd10Code(), condition.getConditionName(),
                            LocalDate.ofInstant(row.getDiagnosedAt(), zone),
                            condition.getNotifyWithinHours());
                })
                .toList();
    }

    private static int distinctPatients(List<SurveillanceDtos.NotifiableCaseResponse> cases) {
        return (int) cases.stream().map(SurveillanceDtos.NotifiableCaseResponse::patientId)
                .distinct().count();
    }

    /**
     * The line list as CSV.
     *
     * <p>Through {@code CsvWriter} like every other export on the platform, and here the
     * formula-injection neutralising it does earns its place twice over: this file carries an MRN,
     * and an MRN beginning with a character a spreadsheet treats as a formula is exactly the kind
     * of value nobody thinks to check.
     */
    private static String csv(List<SurveillanceDtos.NotifiableCaseResponse> cases) {
        CsvWriter csv = new CsvWriter(LINE_LIST_HEADER);
        for (SurveillanceDtos.NotifiableCaseResponse row : cases) {
            csv.row(List.of(row.patientMrn(), row.icd10Code(), row.conditionName(),
                    String.valueOf(row.diagnosedOn()), String.valueOf(row.notifyWithinHours())));
        }
        return csv.toCsv();
    }

    private static String filename(Window window) {
        return "notifiable-line-list-%s-to-%s.csv".formatted(window.from(), window.to());
    }

    /** The period, resolved once, in the hospital's zone. */
    private record Window(LocalDate from, LocalDate to, Instant fromInstant, Instant toInstant) {
    }

    private Window window(Filters filters) {
        LocalDate from = filters.from() == null
                ? today().minusDays(DEFAULT_WINDOW_DAYS) : filters.from();
        LocalDate to = filters.to() == null ? today() : filters.to();
        if (to.isBefore(from)) {
            throw new BadRequestException(("A period from %s to %s runs backwards.")
                    .formatted(from, to));
        }
        return new Window(from, to, startOf(from), startOf(to.plusDays(1)));
    }

    /** A filename carrying the period, so two downloads in one folder are still tellable apart. */
    public String csvFilename(Filters filters) {
        LocalDate from = filters.from() == null
                ? today().minusDays(DEFAULT_WINDOW_DAYS) : filters.from();
        LocalDate to = filters.to() == null ? today() : filters.to();
        return "notifiable-%s-to-%s.csv".formatted(from, to);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }
}
