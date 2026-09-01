package com.hms.identity.repo;

import com.hms.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Free-text search across username, full name and email, optionally narrowed to one role.
     *
     * <p>Both parameters are always-present LIKE patterns ({@code %} means "no filter") rather than
     * nullable values: a bare {@code :param is null} check sends an untyped null that PostgreSQL
     * infers as {@code bytea}, and {@code lower(bytea)} does not exist.
     *
     * @param pattern     lower-cased {@code %term%} pattern, or {@code %} for everything
     * @param rolePattern exact role code, or {@code %} for any role
     */
    @Query(value = """
            select distinct u from User u left join u.roles r
            where (lower(u.username) like :pattern
                   or lower(u.fullName) like :pattern
                   or lower(u.email) like :pattern)
              and (r.code like :rolePattern or r.code is null)
            """,
            countQuery = """
            select count(distinct u) from User u left join u.roles r
            where (lower(u.username) like :pattern
                   or lower(u.fullName) like :pattern
                   or lower(u.email) like :pattern)
              and (r.code like :rolePattern or r.code is null)
            """)
    Page<User> search(@Param("pattern") String pattern, @Param("rolePattern") String rolePattern, Pageable pageable);
}
