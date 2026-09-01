package com.hms.laboratory.repo;

import com.hms.laboratory.domain.Analyzer;
import com.hms.laboratory.domain.DeviceMessage;
import com.hms.laboratory.domain.HistogramRecord;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.LabTestCatalogEntry;
import com.hms.laboratory.domain.ReferenceRange;
import com.hms.laboratory.domain.Specimen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceMessageRepository extends JpaRepository<DeviceMessage, UUID> {

    Page<DeviceMessage> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
