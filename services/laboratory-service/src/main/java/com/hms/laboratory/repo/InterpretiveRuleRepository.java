package com.hms.laboratory.repo;

import com.hms.laboratory.domain.InterpretiveRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterpretiveRuleRepository extends JpaRepository<InterpretiveRule, UUID> {

    /**
     * Active rules with their conditions, in display order.
     *
     * <p>The entity graph is not optional: without it this is one query for the rules and then one
     * per rule for its conditions, on a path that runs for every report rendered.
     */
    @EntityGraph(attributePaths = "conditions")
    List<InterpretiveRule> findByActiveTrueOrderByDisplayOrderAsc();

    @EntityGraph(attributePaths = "conditions")
    List<InterpretiveRule> findAllByOrderByDisplayOrderAsc();

    Optional<InterpretiveRule> findByCode(String code);
}
