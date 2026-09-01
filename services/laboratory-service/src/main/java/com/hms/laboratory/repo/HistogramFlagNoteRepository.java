package com.hms.laboratory.repo;

import com.hms.laboratory.domain.HistogramFlagNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistogramFlagNoteRepository extends JpaRepository<HistogramFlagNote, String> {

    List<HistogramFlagNote> findByActiveTrue();
}
