package com.hms.imaging.service;

import com.hms.imaging.domain.ImagingInstance;
import com.hms.imaging.domain.ImagingReport;
import com.hms.imaging.domain.ImagingSeries;
import com.hms.imaging.domain.ImagingStudy;
import com.hms.imaging.repo.InstanceRepository;
import com.hms.imaging.repo.ReportRepository;
import com.hms.imaging.repo.SeriesRepository;
import com.hms.imaging.repo.StudyRepository;
import com.hms.imaging.web.dto.ImagingDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads studies and assembles them for a response.
 *
 * <p>Separate from the ingest and the ordering services because both of those need it and neither
 * should depend on the other. It holds no rules of its own: the narrowing is applied by whoever
 * called it, which is stated here so nobody adds a read path that quietly bypasses one.
 */
@Service
public class StudyReadService {

    private final StudyRepository studies;
    private final SeriesRepository series;
    private final InstanceRepository instances;
    private final ReportRepository reports;

    public StudyReadService(StudyRepository studies,
                            SeriesRepository series,
                            InstanceRepository instances,
                            ReportRepository reports) {
        this.studies = studies;
        this.series = series;
        this.instances = instances;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public List<ImagingDtos.StudyResponse> forOrder(UUID orderId) {
        return studies.findByOrderIdOrderByReceivedAtDesc(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Everything that arrived and matched no order.
     *
     * <p>A work list rather than a report: each row is a study sitting on a scanner's disk that this
     * platform cannot attach to anybody, and somebody has to resolve it. Kept rather than discarded
     * because the images exist whatever the platform makes of them, and filing them against the
     * closest-looking patient would be worse than filing them against none.
     */
    @Transactional(readOnly = true)
    public List<ImagingDtos.StudyResponse> unmatched(Pageable pageable) {
        return studies.findByOrderIdIsNullOrderByReceivedAtDesc(pageable).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ImagingDtos.StudyResponse toResponse(ImagingStudy study) {
        List<ImagingDtos.SeriesResponse> seriesResponses =
                series.findByStudyIdOrderBySeriesNumberAsc(study.getId()).stream()
                        .map(this::toResponse)
                        .toList();
        ImagingDtos.ReportResponse report = reports.findByStudyId(study.getId())
                .map(StudyReadService::toResponse)
                .orElse(null);
        return new ImagingDtos.StudyResponse(study.getId(), study.getStudyInstanceUid(),
                study.getOrderId(), study.getAccessionNo(), study.getPatientMrn(),
                study.getModality(), study.getStudyDescription(), study.getStudyDate(),
                study.getInstitution(), study.getReferringPhysician(), study.getReceivedAt(),
                seriesResponses, report);
    }

    private ImagingDtos.SeriesResponse toResponse(ImagingSeries s) {
        List<ImagingInstance> members = instances.findBySeriesIdOrderByInstanceNumberAsc(s.getId());
        // Stored when every instance in the series has somewhere to be read from. Any instance
        // without a URI makes the series unviewable, so reporting "stored" for a partial one would
        // send a radiologist to an archive that has half of it.
        boolean stored = !members.isEmpty()
                && members.stream().allMatch(i -> i.getStorageUri() != null);
        return new ImagingDtos.SeriesResponse(s.getId(), s.getSeriesInstanceUid(),
                s.getSeriesNumber(), s.getModality(), s.getSeriesDescription(), s.getBodyPart(),
                members.size(), stored);
    }

    static ImagingDtos.ReportResponse toResponse(ImagingReport r) {
        return new ImagingDtos.ReportResponse(r.getId(), r.getStudyId(), r.getFindings(),
                r.getImpression(), r.getStatus(), r.getReportedBy(), r.getReportedAt(),
                r.getSignedBy(), r.getSignedAt(), r.getAmendedFrom(), r.getAmendedReason());
    }
}
