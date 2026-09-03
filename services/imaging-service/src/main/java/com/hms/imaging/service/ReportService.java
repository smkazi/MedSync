package com.hms.imaging.service;

import com.hms.common.audit.AuditService;
import com.hms.common.careteam.CareRelationshipClient;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.imaging.domain.ImagingEnums;
import com.hms.imaging.domain.ImagingOrder;
import com.hms.imaging.domain.ImagingReport;
import com.hms.imaging.domain.ImagingStudy;
import com.hms.imaging.repo.OrderRepository;
import com.hms.imaging.repo.ReportRepository;
import com.hms.imaging.repo.StudyRepository;
import com.hms.imaging.web.dto.ImagingDtos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The radiologist's report: written, signed, and amended if it has to be.
 *
 * <p>Signing is release. There is no second step and no separate "publish", exactly as verifying a
 * laboratory result is release there — because the alternative is a report that is finished and
 * invisible, which is how a finding waits three days in a system nobody blames.
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final StudyRepository studies;
    private final OrderRepository orders;
    private final CareRelationshipClient careRelationships;
    private final EventPublisher events;
    private final AuditService audit;

    public ReportService(ReportRepository reports,
                         StudyRepository studies,
                         OrderRepository orders,
                         CareRelationshipClient careRelationships,
                         EventPublisher events,
                         AuditService audit) {
        this.reports = reports;
        this.studies = studies;
        this.orders = orders;
        this.careRelationships = careRelationships;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Writes or revises the draft for a study.
     *
     * <p>One report per study, so this creates the row the first time and rewrites it after. A
     * second radiologist picking up a colleague's unsigned draft is normal work — handover happens
     * mid-list — so the author is updated rather than the write refused, and who signed it is the
     * name that ends up on the record.
     */
    @Transactional
    public ImagingDtos.ReportResponse draft(UUID studyId, ImagingDtos.ReportRequest request) {
        ImagingStudy study = requireStudy(studyId);
        String author = CurrentUser.usernameOrSystem();
        ImagingReport report = reports.findByStudyId(studyId).orElse(null);
        if (report == null) {
            report = new ImagingReport(studyId, request.findings().trim(),
                    request.impression().trim(), author);
        } else {
            report.reviseDraft(request.findings().trim(), request.impression().trim(), author);
        }
        reports.save(report);
        audit.record("IMAGING_REPORT_DRAFTED", "ImagingReport", report.getId(),
                describe(study, "draft"));
        return StudyReadService.toResponse(report);
    }

    /**
     * Signs the report, which releases it.
     *
     * <p>The order moves to {@code REPORTED} in the same transaction, and the event that goes out
     * is the one downstream services care about: billing prices the examination, and notification
     * tells the patient a report is ready — carrying the procedure code and not a word of what it
     * says, which is that module's standing rule.
     */
    @Transactional
    public ImagingDtos.ReportResponse sign(UUID studyId) {
        ImagingStudy study = requireStudy(studyId);
        ImagingReport report = requireReport(studyId);
        report.sign(CurrentUser.usernameOrSystem());
        reports.save(report);

        Optional<ImagingOrder> order = Optional.ofNullable(study.getOrderId())
                .flatMap(orders::findById);
        order.ifPresent(o -> {
            o.transitionTo(ImagingEnums.OrderStatus.REPORTED);
            publish("imaging.report.signed", o, study, report);
        });
        if (order.isEmpty()) {
            // A report on an unmatched study is legitimate — a radiologist can read images that
            // arrived for nobody — and it cannot be billed or notified, because there is no patient
            // to bill or tell. Said out loud rather than silently skipped.
            audit.record("IMAGING_REPORT_SIGNED_UNMATCHED", "ImagingReport", report.getId(),
                    "%s has no order, so nothing was billed or notified"
                            .formatted(study.getStudyInstanceUid()));
        }
        audit.record("IMAGING_REPORT_SIGNED", "ImagingReport", report.getId(),
                describe(study, "signed"));
        return StudyReadService.toResponse(report);
    }

    /**
     * Supersedes a signed report.
     *
     * <p>The previous text is kept, because a report that was acted on is part of the record whether
     * or not it was later corrected — and the reason is required, because an amendment to something
     * a clinician may already have treated from has to explain itself.
     */
    @Transactional
    public ImagingDtos.ReportResponse amend(UUID studyId, ImagingDtos.AmendRequest request) {
        ImagingStudy study = requireStudy(studyId);
        ImagingReport report = requireReport(studyId);
        report.amend(request.findings().trim(), request.impression().trim(),
                request.reason().trim(), CurrentUser.usernameOrSystem());
        reports.save(report);

        Optional.ofNullable(study.getOrderId()).flatMap(orders::findById)
                .ifPresent(o -> publish("imaging.report.amended", o, study, report));
        // The reason is operator-supplied text about a clinical finding, so it stays out of the
        // audit detail -- the platform's standing rule, the same one break-glass follows. It lives
        // on the report, where clinical text belongs.
        audit.record("IMAGING_REPORT_AMENDED", "ImagingReport", report.getId(),
                describe(study, "amended"));
        return StudyReadService.toResponse(report);
    }

    /**
     * Reads a study's report.
     *
     * <p>Narrowed for the clinical roles, and a draft is answered as a draft rather than as
     * findings: provisional text read as an answer is the failure the laboratory's verify step
     * exists to prevent, and radiology has the same exposure.
     */
    @Transactional(readOnly = true)
    public ImagingDtos.ReportResponse read(UUID studyId) {
        ImagingStudy study = requireStudy(studyId);
        if (study.getPatientId() != null) {
            careRelationships.requirePatientAccess(study.getPatientId());
        }
        return StudyReadService.toResponse(requireReport(studyId));
    }

    private ImagingStudy requireStudy(UUID studyId) {
        return studies.findById(studyId)
                .orElseThrow(() -> NotFoundException.of("ImagingStudy", studyId));
    }

    private ImagingReport requireReport(UUID studyId) {
        return reports.findByStudyId(studyId).orElseThrow(() -> new ConflictException(
                "This study has no report yet. Write the findings before signing them."));
    }

    private static String describe(ImagingStudy study, String what) {
        return "%s %s (%s)".formatted(study.getStudyInstanceUid(), what,
                study.getAccessionNo() == null ? "unmatched" : study.getAccessionNo());
    }

    private void publish(String type, ImagingOrder order, ImagingStudy study,
                         ImagingReport report) {
        events.publish(Topics.IMAGING, DomainEvent.of(type, "ImagingReport", report.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("patientId", order.getPatientId().toString(),
                        "mrn", order.getPatientMrn(),
                        "accessionNo", order.getAccessionNo(),
                        // The code, not a count: billing prices an examination, and the laboratory's
                        // release event had to learn this the hard way.
                        "procedureCode", order.getProcedureCode(),
                        "procedureName", order.getProcedureName(),
                        "studyInstanceUid", study.getStudyInstanceUid(),
                        "status", report.getStatus().name())));
    }
}
