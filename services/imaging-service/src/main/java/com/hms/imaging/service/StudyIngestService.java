package com.hms.imaging.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.imaging.dicom.DicomParser;
import com.hms.imaging.dicom.DicomTag;
import com.hms.imaging.domain.ImagingEnums;
import com.hms.imaging.domain.ImagingInstance;
import com.hms.imaging.domain.ImagingOrder;
import com.hms.imaging.domain.ImagingSeries;
import com.hms.imaging.domain.ImagingStudy;
import com.hms.imaging.repo.InstanceRepository;
import com.hms.imaging.repo.OrderRepository;
import com.hms.imaging.repo.SeriesRepository;
import com.hms.imaging.repo.StudyRepository;
import com.hms.imaging.web.dto.ImagingDtos;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers what came off a modality.
 *
 * <p>One instance per call, which is how DICOM itself works: a scanner sends images one at a time,
 * and a study is assembled from however many arrive. So this method is called once per file and is
 * idempotent per SOP instance UID — re-sending the same image updates its row rather than creating a
 * second one, because a resend is what a scanner does when it is unsure the first attempt landed.
 *
 * <p><strong>Matching is by accession number and nothing else.</strong> The modality copies it off
 * the worklist and writes it into every image, so it is the one field that survives the trip. The
 * patient identifiers in the header are whatever was typed at the console; matching on those would
 * file a study against the wrong visit the first time somebody was scanned twice in a day, and
 * against the wrong patient the first time a name was mistyped. So a header whose accession number
 * names no order produces a study that is registered, flagged, and put on a list for somebody to
 * resolve — never guessed at.
 */
@Service
public class StudyIngestService {

    private static final Logger log = LoggerFactory.getLogger(StudyIngestService.class);

    /** DICOM writes dates as {@code YYYYMMDD}, with no separators. */
    private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final StudyRepository studies;
    private final SeriesRepository series;
    private final InstanceRepository instances;
    private final OrderRepository orders;
    private final ImageStore images;
    private final EventPublisher events;
    private final AuditService audit;

    public StudyIngestService(StudyRepository studies,
                              SeriesRepository series,
                              InstanceRepository instances,
                              OrderRepository orders,
                              ImageStore images,
                              EventPublisher events,
                              AuditService audit) {
        this.studies = studies;
        this.series = series;
        this.instances = instances;
        this.orders = orders;
        this.images = images;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public ImagingDtos.IngestResponse ingest(byte[] bytes) {
        DicomParser.DicomHeader header;
        try {
            header = DicomParser.parse(bytes);
        } catch (DicomParser.DicomParseException ex) {
            // The parser's own message says what was wrong with the bytes; it is a caller error and
            // is reported as one rather than becoming a 500 about a file somebody uploaded.
            throw new BadRequestException(ex.getMessage());
        }

        String studyUid = required(header, DicomTag.STUDY_INSTANCE_UID, "study instance UID");
        String seriesUid = required(header, DicomTag.SERIES_INSTANCE_UID, "series instance UID");
        String sopUid = required(header, DicomTag.SOP_INSTANCE_UID, "SOP instance UID");

        ImagingStudy study = studies.findByStudyInstanceUid(studyUid)
                .orElseGet(() -> newStudy(studyUid, header));
        ImagingSeries acquisition = series.findBySeriesInstanceUid(seriesUid)
                .orElseGet(() -> newSeries(seriesUid, study, header));

        Optional<String> storedAt = images.store(studyUid, seriesUid, sopUid, bytes);
        ImagingInstance instance = instances.findBySopInstanceUid(sopUid)
                .orElseGet(() -> new ImagingInstance(sopUid, acquisition.getId()));
        instance.setSopClassUid(header.get(DicomTag.SOP_CLASS_UID).orElse(null));
        instance.setInstanceNumber(header.getInt(DicomTag.INSTANCE_NUMBER).orElse(null));
        instance.setRowsCount(header.getInt(DicomTag.ROWS).orElse(null));
        instance.setColumnsCount(header.getInt(DicomTag.COLUMNS).orElse(null));
        instance.setTransferSyntaxUid(header.transferSyntaxUid());
        instance.setByteCount((long) bytes.length);
        storedAt.ifPresent(instance::setStorageUri);
        instances.save(instance);

        // The order moves to ACQUIRED on the first image, not the last: a modality does not tell
        // anybody when it has finished, and a study waiting for a "complete" signal that never
        // arrives is a study nobody reports. A radiologist opens it when the series look right.
        boolean matched = !study.isUnmatched();
        if (matched) {
            orders.findById(study.getOrderId()).ifPresent(order -> {
                if (order.canTransitionTo(ImagingEnums.OrderStatus.ACQUIRED)) {
                    order.transitionTo(ImagingEnums.OrderStatus.ACQUIRED);
                    publish("imaging.study.acquired", order, study);
                }
            });
        }

        audit.record("IMAGING_STUDY_RECEIVED", "ImagingStudy", study.getId(),
                matched ? "%s matched %s".formatted(study.getStudyInstanceUid(),
                        study.getAccessionNo())
                        : "%s matched no order (accession '%s')".formatted(
                                study.getStudyInstanceUid(), nullToDash(study.getAccessionNo())));

        return new ImagingDtos.IngestResponse(study.getId(), studyUid, study.getAccessionNo(),
                matched, study.getOrderId(), storedAt.isPresent(), storedAt.orElse(null),
                message(matched, study, storedAt.isPresent()));
    }

    private ImagingStudy newStudy(String studyUid, DicomParser.DicomHeader header) {
        ImagingStudy study = new ImagingStudy(studyUid);
        String accession = header.getOrEmpty(DicomTag.ACCESSION_NUMBER).trim();
        study.setAccessionNo(accession.isEmpty() ? null : accession);
        study.setModality(header.get(DicomTag.MODALITY).orElse(null));
        study.setStudyDescription(header.get(DicomTag.STUDY_DESCRIPTION).orElse(null));
        study.setStudyDate(parseDate(header.getOrEmpty(DicomTag.STUDY_DATE)));
        study.setInstitution(header.get(DicomTag.INSTITUTION_NAME).orElse(null));
        study.setReferringPhysician(header.get(DicomTag.REFERRING_PHYSICIAN_NAME).orElse(null));

        if (!accession.isEmpty()) {
            orders.findByAccessionNo(accession).ifPresent(study::matchTo);
        }
        if (study.isUnmatched()) {
            // Logged at WARN because it is a thing somebody must act on, and the log is where a
            // department notices it before anybody opens the reconciliation screen.
            log.warn("Study {} arrived with accession '{}', which matches no order", studyUid,
                    accession.isEmpty() ? "(none)" : accession);
        }
        return studies.save(study);
    }

    private ImagingSeries newSeries(String seriesUid, ImagingStudy study,
                                    DicomParser.DicomHeader header) {
        ImagingSeries acquisition = new ImagingSeries(seriesUid, study.getId());
        acquisition.setSeriesNumber(header.getInt(DicomTag.SERIES_NUMBER).orElse(null));
        acquisition.setModality(header.get(DicomTag.MODALITY).orElse(null));
        acquisition.setSeriesDescription(header.get(DicomTag.SERIES_DESCRIPTION).orElse(null));
        acquisition.setBodyPart(header.get(DicomTag.BODY_PART_EXAMINED).orElse(null));
        return series.save(acquisition);
    }

    /**
     * What the radiographer is told, in words rather than in flags.
     *
     * <p>Three facts, and each of them is one somebody acts on: whether it found its order, whether
     * the pixels were kept, and what to do if not. A screen that reported only success would leave
     * a department believing images are archived when no archive is configured.
     */
    private static String message(boolean matched, ImagingStudy study, boolean stored) {
        StringBuilder said = new StringBuilder();
        said.append(matched
                ? "Filed against " + study.getAccessionNo() + "."
                : "This study's accession number matches no order, so it is registered without a"
                        + " patient and is on the unmatched list for somebody to resolve.");
        said.append(stored
                ? " The image was stored."
                : " No archive is configured, so the image itself was not kept — the study is"
                        + " registered from its header alone.");
        return said.toString();
    }

    private static String required(DicomParser.DicomHeader header, int tag, String what) {
        return header.get(tag).orElseThrow(() -> new BadRequestException(
                "This file carries no %s, so there is nothing to register it as".formatted(what)));
    }

    private static LocalDate parseDate(String value) {
        String trimmed = value.trim();
        if (trimmed.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed, DICOM_DATE);
        } catch (DateTimeParseException ex) {
            // A malformed study date is not worth refusing an image over: it is a label, and the
            // study's own received_at is the timestamp anything depends on.
            return null;
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void publish(String type, ImagingOrder order, ImagingStudy study) {
        events.publish(Topics.IMAGING, DomainEvent.of(type, "ImagingStudy", study.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("patientId", order.getPatientId().toString(),
                        "mrn", order.getPatientMrn(),
                        "accessionNo", order.getAccessionNo(),
                        "procedureCode", order.getProcedureCode(),
                        "studyInstanceUid", study.getStudyInstanceUid())));
    }
}
