package com.hms.admissions.repo;

import com.hms.admissions.domain.AdmissionEnums;
import com.hms.admissions.domain.BedOccupancy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BedOccupancyRepository extends JpaRepository<BedOccupancy, UUID> {

    /**
     * The current occupant of a bed, if any.
     *
     * <p>A convenience for rendering, <strong>not</strong> the allocation check. Reading this and
     * then inserting is the race the partial unique index exists to lose: two clinicians allocating
     * the last bed both see it free. The insert is the check.
     */
    Optional<BedOccupancy> findByBedIdAndReleasedAtIsNull(UUID bedId);

    List<BedOccupancy> findByReleasedAtIsNull();

    Optional<BedOccupancy> findByOccupantTypeAndOccupantIdAndReleasedAtIsNull(
            AdmissionEnums.OccupantType occupantType, UUID occupantId);
}
