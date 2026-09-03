package com.hms.identity.service;

import com.hms.common.csv.CsvWriter;
import com.hms.common.data.QueryPatterns;
import com.hms.identity.domain.AuditLogEntry;
import com.hms.identity.repo.AuditLogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit report: one filtered query, read on a screen or downloaded as CSV.
 *
 * <p>The filters and the defaulting live here rather than in the controller because both callers
 * need exactly the same ones — a report whose CSV covered a different period from the table above
 * it would be worse than no CSV at all.
 */
@Service
public class AuditReportService {

    /** Column order for the export. Fixed, because somebody will build a spreadsheet on it. */
    private static final List<String> HEADER = List.of(
            "occurredAt", "service", "action", "entity", "entityId", "actorId", "username",
            "correlationId", "detail");

    /** How far back an unbounded report reaches. Long enough to be useful, short enough to return. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final AuditLogRepository repository;
    private final ZoneId zone;
    private final int exportMaxRows;

    public AuditReportService(AuditLogRepository repository,
                              @Value("${hms.audit.zone:Asia/Kolkata}") ZoneId zone,
                              @Value("${hms.audit.export-max-rows:50000}") int exportMaxRows) {
        this.repository = repository;
        this.zone = zone;
        this.exportMaxRows = exportMaxRows;
    }

    /** What a caller asked for. A record so the screen and the export cannot drift apart. */
    public record Filters(String entity, String action, String actorId, String username,
                          LocalDate from, LocalDate to) {
    }

    public Page<AuditLogEntry> search(Filters filters, Pageable pageable) {
        return repository.search(
                QueryPatterns.exactOrAny(filters.entity()),
                QueryPatterns.exactOrAny(filters.action()),
                QueryPatterns.exactOrAny(filters.actorId()),
                QueryPatterns.contains(filters.username()),
                startOf(filters.from() == null ? today().minusDays(DEFAULT_WINDOW_DAYS) : filters.from()),
                // Half-open and exclusive of the day after, so `to = today` includes all of today.
                startOf((filters.to() == null ? today() : filters.to()).plusDays(1)),
                pageable);
    }

    /**
     * The same filtered query as the screen, rendered as CSV.
     *
     * <p>Capped rather than streamed. An unbounded audit table piped into a browser is a request
     * that either times out or exhausts the heap, and the person who asked for it would have no
     * way to tell which. When the cap bites, the last line says so in the file itself — a truncated
     * export that looks complete is the way somebody concludes an action never happened.
     */
    @Transactional(readOnly = true)
    public String toCsv(Filters filters) {
        Page<AuditLogEntry> page = search(filters, PageRequest.of(0, exportMaxRows));
        CsvWriter csv = new CsvWriter(HEADER);
        for (AuditLogEntry entry : page.getContent()) {
            csv.row(List.of(
                    String.valueOf(entry.getOccurredAt()),
                    nullToEmpty(entry.getService()),
                    nullToEmpty(entry.getAction()),
                    nullToEmpty(entry.getEntity()),
                    nullToEmpty(entry.getEntityId()),
                    nullToEmpty(entry.getActorId()),
                    nullToEmpty(entry.getUsername()),
                    nullToEmpty(entry.getCorrelationId()),
                    nullToEmpty(entry.getDetail())));
        }
        if (page.getTotalElements() > page.getNumberOfElements()) {
            csv.row(List.of("TRUNCATED", "", "", "", "", "", "", "",
                    page.getNumberOfElements() + " of " + page.getTotalElements()
                            + " rows exported; narrow the filters or the date range for the rest"));
        }
        return csv.toCsv();
    }

    /** A filename carrying the period, so two downloads in one folder are still tellable apart. */
    public String csvFilename(Filters filters) {
        LocalDate from = filters.from() == null ? today().minusDays(DEFAULT_WINDOW_DAYS) : filters.from();
        LocalDate to = filters.to() == null ? today() : filters.to();
        return "audit-" + from + "-to-" + to + ".csv";
    }

    /**
     * "The 14th" means the hospital's 14th, not the container's. The same decision billing-service
     * makes for its day book, and for the same reason a previous slice got wrong: a UTC container
     * in Asia/Kolkata rolls the date over five and a half hours early, so an auditor asking for
     * yesterday would be shown part of the day before. A deployment that moves one zone must move
     * both.
     */
    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
