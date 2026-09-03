package com.hms.imaging.repo;

import com.hms.imaging.domain.ImagingProcedure;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcedureRepository extends JpaRepository<ImagingProcedure, String> {

    List<ImagingProcedure> findByActiveTrueOrderByModalityAscNameAsc();
}
