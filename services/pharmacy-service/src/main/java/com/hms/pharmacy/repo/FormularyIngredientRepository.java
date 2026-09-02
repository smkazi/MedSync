package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.FormularyIngredient;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormularyIngredientRepository
        extends JpaRepository<FormularyIngredient, FormularyIngredient.Key> {

    /**
     * The ingredients of several products at once.
     *
     * <p>One query for the whole prescription rather than one per item: an interaction check runs
     * over the union of every ingredient on the order, and asking per item would be N round trips
     * to build one set.
     */
    @Query("select i from FormularyIngredient i where i.id.drugCode in :codes")
    List<FormularyIngredient> findByDrugCodes(@Param("codes") Collection<String> codes);
}
