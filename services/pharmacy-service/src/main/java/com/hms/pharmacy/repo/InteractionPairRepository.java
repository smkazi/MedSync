package com.hms.pharmacy.repo;

import com.hms.pharmacy.domain.InteractionPair;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InteractionPairRepository extends JpaRepository<InteractionPair, UUID> {

    Optional<InteractionPair> findByIngredientAAndIngredientB(String a, String b);

    /**
     * Every pairing among a set of ingredients.
     *
     * <p>Both columns are matched against the same set, which is what makes one query enough: the
     * rows are stored with the pair sorted, so a pairing between two members of the set has both
     * of its columns inside the set. Comparing pairwise in Java would be the same answer in N²
     * round trips.
     */
    @Query("""
            select p from InteractionPair p
             where p.ingredientA in :ingredients
               and p.ingredientB in :ingredients
             order by p.severity desc, p.ingredientA
            """)
    List<InteractionPair> findAmong(@Param("ingredients") Collection<String> ingredients);

    List<InteractionPair> findAllByOrderByIngredientAAscIngredientBAsc();
}
