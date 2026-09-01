package com.hms.laboratory.repo;

import com.hms.laboratory.domain.ReportGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportGroupRepository extends JpaRepository<ReportGroup, String> {

    List<ReportGroup> findAllByOrderByDisplayOrderAsc();
}
