package com.hms.laboratory.service;

import com.hms.common.error.BadRequestException;
import com.hms.laboratory.client.PatientDirectoryClient;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.ReferenceRange;
import com.hms.laboratory.domain.ReportGroup;
import com.hms.laboratory.domain.ReportParameterGroup;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.report.LabReportRenderer;
import com.hms.laboratory.report.ReportContent;
import com.hms.laboratory.report.ReportLayout;
import com.hms.laboratory.report.ReportRow;
import com.hms.laboratory.report.ReportSection;
import com.hms.laboratory.repo.LabResultRepository;
import com.hms.laboratory.repo.ReportGroupRepository;
import com.hms.laboratory.repo.ReportParameterGroupRepository;
import com.hms.laboratory.web.dto.LabDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a pathology report and hands it to the renderer.
 *
 * <p>Splits cleanly in two on purpose: this class resolves — the patient's name across a service
 * boundary, the reference intervals, the section layout, the interpretation — and
 * {@link LabReportRenderer} draws. The renderer takes records and touches no repository, which is
 * what lets a PDF be asserted in a unit test with no database and no patient-service running.
 */
@Service
public class LabReportService {

    /** Groups with no configured members still print nothing; this is the fallback for strays. */
    private static final String FALLBACK_GROUP = "OTHER";

    private final LabOrderService orders;
    private final LabResultRepository results;
    private final ReferenceRangeService ranges;
    private final InterpretationService interpretations;
    private final PatientDirectoryClient patients;
    private final ReportGroupRepository groups;
    private final ReportParameterGroupRepository parameterGroups;
    private final ReportLayout layout;

    public LabReportService(LabOrderService orders,
                            LabResultRepository results,
                            ReferenceRangeService ranges,
                            InterpretationService interpretations,
                            PatientDirectoryClient patients,
                            ReportGroupRepository groups,
                            ReportParameterGroupRepository parameterGroups,
                            @Value("${hms.laboratory.report.lab-name:}") String labName,
                            @Value("${hms.laboratory.report.address:}") String labAddress,
                            @Value("${hms.laboratory.report.city:}") String labCity,
                            @Value("${hms.laboratory.report.pathologist:}") String pathologist,
                            @Value("${hms.laboratory.report.technician:}") String technician,
                            @Value("${hms.laboratory.report.footer-note:}") String footerNote) {
        this.orders = orders;
        this.results = results;
        this.ranges = ranges;
        this.interpretations = interpretations;
        this.patients = patients;
        this.groups = groups;
        this.parameterGroups = parameterGroups;
        // Blank by default and blank fields do not render. The source project shipped one
        // laboratory's name and staff as fallbacks; a report that prints somebody else's letterhead
        // because nobody configured it is worse than one that prints none.
        this.layout = new ReportLayout(labName, labAddress, labCity, pathologist, technician, footerNote);
    }

    /**
     * Renders the report for an order.
     *
     * @throws BadRequestException if the order carries no results yet. There is nothing to report,
     *                            and an empty report filed in a chart reads as a normal one.
     */
    @Transactional(readOnly = true)
    public Rendered render(java.util.UUID orderId, String bearerToken) {
        return render(orderId, bearerToken, null);
    }

    /**
     * The same report, with the header identity supplied rather than looked up.
     *
     * <p>For the portal, where the caller <em>is</em> the patient and therefore cannot open
     * {@code /patients/{id}} to have their own name read back to them. The portal reads it from
     * {@code /portal/me} with its own token and passes it in here, so there is one renderer and one
     * report rather than a second one that could drift from the printed original.
     *
     * @param known the identity to print, or null to look it up as the staff path does
     */
    @Transactional(readOnly = true)
    public Rendered render(java.util.UUID orderId, String bearerToken,
                           PatientDirectoryClient.PatientIdentity known) {
        LabOrder order = orders.requireDetail(orderId);
        // Cancelled is checked before emptiness so the caller gets the accurate reason. Both end in
        // a 400, but "this order was cancelled" and "no results yet" send somebody to different
        // places, and the first is the more useful thing to be told.
        if (order.getStatus() == LabEnums.OrderStatus.CANCELLED) {
            throw new BadRequestException("This order was cancelled; no report can be issued for it");
        }
        List<LabResult> measured = results.findByOrderIdOrderByParameter(orderId);
        if (measured.isEmpty()) {
            throw new BadRequestException(
                    "No results have been recorded for this order yet, so there is nothing to report");
        }

        PatientDirectoryClient.PatientIdentity patient =
                known != null ? known : patients.require(order.getPatientId(), bearerToken);

        boolean verified = order.getStatus() == LabEnums.OrderStatus.VERIFIED;
        List<ReportRow> rows = rowsFor(order, measured);
        LabDtos.InterpretationView interpretation = interpretations.interpret(measured, null);

        ReportContent content = new ReportContent(
                titleFor(order),
                patient.fullName(),
                order.getPatientMrn(),
                ageSex(patient),
                accessionOf(order),
                order.getOrderedBy(),
                Instant.now(),
                verified,
                verifiedBy(measured),
                interpretation.notes(),
                interpretation.morphology(),
                qrSummary(patient, order, rows));

        byte[] pdf = new LabReportRenderer(layout).render(content, sectionsFor(rows));
        return new Rendered(pdf, fileNameFor(order), verified);
    }

    // ---- assembly --------------------------------------------------------------

    private List<ReportRow> rowsFor(LabOrder order, List<LabResult> measured) {
        List<ReportRow> rows = new ArrayList<>(measured.size());
        for (LabResult result : measured) {
            Optional<ReferenceRange> range = ranges.find(result.getParameter(), order.getPatientSex());
            String flag = result.getFlag() == null ? "" : result.getFlag().trim();
            rows.add(new ReportRow(
                    result.getParameter(),
                    range.map(ReferenceRange::getDisplayName)
                            .filter(name -> name != null && !name.isBlank())
                            .orElse(result.getParameter()),
                    // An unmeasurable reading is an em dash, not a blank: a blank cell reads as a
                    // test that was not requested, which is a different statement entirely.
                    blankToDash(result.getValue()),
                    nullToEmpty(result.getUnit()),
                    blankToDash(result.getRefText()),
                    flag,
                    result.isAbnormal()));
        }
        return rows;
    }

    /**
     * Splits rows into the configured sections, in configured order.
     *
     * <p>A parameter with no configured section lands in the trailing fallback group rather than
     * being dropped. Silently losing a measured value off a clinical report because a lookup table
     * was incomplete is the worst failure available here.
     */
    private List<ReportSection> sectionsFor(List<ReportRow> rows) {
        Map<String, String> groupOf = new HashMap<>();
        Map<String, Short> orderOf = new HashMap<>();
        for (ReportParameterGroup mapping : parameterGroups.findAll()) {
            String key = mapping.getParameter().toUpperCase(Locale.ROOT);
            groupOf.put(key, mapping.getGroupCode());
            orderOf.put(key, mapping.getDisplayOrder());
        }

        Map<String, List<ReportRow>> byGroup = new LinkedHashMap<>();
        Map<String, Short> rowOrder = new HashMap<>();
        for (ReportRow row : rows) {
            // Keyed on the instrument's parameter code, not the display name. Matching on the
            // display name would silently drop every row into "Other" the day a laboratory renames
            // "Haemoglobin" to "Hb".
            String key = row.parameter().toUpperCase(Locale.ROOT);
            String group = groupOf.getOrDefault(key, FALLBACK_GROUP);
            byGroup.computeIfAbsent(group, unused -> new ArrayList<>()).add(row);
            rowOrder.put(key, orderOf.getOrDefault(key, (short) 100));
        }

        List<ReportSection> sections = new ArrayList<>();
        List<ReportGroup> configured = groups.findAllByOrderByDisplayOrderAsc();
        for (ReportGroup group : configured) {
            List<ReportRow> members = byGroup.remove(group.getCode());
            if (members == null || members.isEmpty()) {
                continue;
            }
            members.sort(Comparator.comparing(row ->
                    rowOrder.getOrDefault(row.parameter().toUpperCase(Locale.ROOT), (short) 100)));
            sections.add(new ReportSection(group.getTitle(), members));
        }
        // Anything mapped to a group code with no row in report_groups still prints.
        byGroup.forEach((code, members) -> sections.add(new ReportSection(code, members)));
        return sections;
    }

    /**
     * The plain-text summary the QR code carries.
     *
     * <p>Text, never a link — copied deliberately from the source. The code is printed on a sheet the
     * reader already holds, so it discloses nothing the paper does not; it re-encodes what is in
     * their hand into something a phone can read with no portal, no login and no internet. The
     * opposite decision from the notification path, which will carry no results at all, because
     * there the channel reaches a stored phone number that may be stale or shared.
     */
    private String qrSummary(PatientDirectoryClient.PatientIdentity patient, LabOrder order,
                             List<ReportRow> rows) {
        StringBuilder text = new StringBuilder(256);
        if (layout.labName() != null && !layout.labName().isBlank()) {
            text.append(layout.labName()).append('\n');
        }
        text.append("Patient : ").append(patient.fullName()).append('\n');
        text.append("Age/Sex : ").append(ageSex(patient)).append('\n');
        text.append("Sample  : ").append(accessionOf(order)).append('\n');
        text.append("------------------------\n");

        int width = rows.stream().mapToInt(row -> row.displayName().length()).max().orElse(0);
        width = Math.min(width, 14);
        for (ReportRow row : rows) {
            String name = row.displayName();
            text.append(name.length() > width ? name.substring(0, width) : pad(name, width));
            text.append(" : ").append(row.value());
            if (!row.unit().isBlank()) {
                text.append(' ').append(row.unit());
            }
            if (!row.flag().isBlank()) {
                text.append(" [").append(row.flag()).append(']');
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private String titleFor(LabOrder order) {
        List<String> names = order.getItems().stream()
                .map(item -> item.getTestName() == null || item.getTestName().isBlank()
                        ? item.getTestCode() : item.getTestName())
                .toList();
        return names.isEmpty() ? "Laboratory report" : String.join(", ", names);
    }

    /**
     * "44 yrs / F".
     *
     * <p>The initial, not the word. patient-service answers with its own enum ({@code FEMALE}), which
     * printed on a report header reads as shouting, and the laboratory's own records use M/F anyway -
     * so the two vocabularies are reconciled here at the boundary rather than either side changing.
     */
    private static String ageSex(PatientDirectoryClient.PatientIdentity patient) {
        String age = patient.age() == null ? "" : patient.age() + " yrs";
        String sex = patient.sex() == null || patient.sex().isBlank()
                ? "" : patient.sex().trim().substring(0, 1).toUpperCase(Locale.ROOT);
        if (age.isEmpty()) {
            return sex;
        }
        return sex.isEmpty() ? age : age + " / " + sex;
    }

    /**
     * The most recent specimen's accession number.
     *
     * <p>Nulls and blanks are filtered before the reduce. The column is {@code NOT NULL} so this
     * cannot happen today, but a stream that can yield null makes the reduce throw rather than
     * return empty - and the caller then builds a filename from it. Cheap to defend, and the
     * analyser was right to ask.
     */
    private static String accessionOf(LabOrder order) {
        return order.getSpecimens().stream()
                .map(Specimen::getAccessionNo)
                .filter(accession -> accession != null && !accession.isBlank())
                .reduce((first, second) -> second)
                .orElse("not collected");
    }

    private static String verifiedBy(List<LabResult> measured) {
        return measured.stream()
                .map(LabResult::getVerifiedBy)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("");
    }

    /**
     * The download filename, from the accession number.
     *
     * <p>No fallback branch on the order id: {@link #accessionOf} already answers "not collected" for
     * an order with no specimen, which survives the strip as {@code notcollected}, so the empty case
     * was unreachable - and reaching for {@code order.getId()} there was a null dereference SpotBugs
     * was right to flag. Deleting the dead branch fixes both at once.
     */
    private static String fileNameFor(LabOrder order) {
        return "report-" + accessionOf(order).replaceAll("[^A-Za-z0-9._-]", "") + ".pdf";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** The rendered document, plus what the caller needs to serve it. */
    public record Rendered(byte[] pdf, String fileName, boolean verified) {

        public Rendered {
            pdf = pdf == null ? new byte[0] : pdf.clone();
        }

        @Override
        public byte[] pdf() {
            return pdf.clone();
        }
    }
}
