package com.hms.identity.repo;

import com.hms.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * <p>The role predicate is {@code :rolePattern = '%' or r.code like :rolePattern}, and the
     * shape matters. It used to read {@code r.code like :rolePattern or r.code is null}, so that
     * an unfiltered search would still return users with no roles - but the left join makes
     * {@code r.code} null for exactly those users, which meant they matched <em>every</em> role
     * filter. Asking for pathologists returned every role-less account as well. Comparing the
     * pattern to {@code '%'} says "no filter was supplied" explicitly, which is what was meant.
     *
     * @param pattern     lower-cased {@code %term%} pattern, or {@code %} for everything
     * @param rolePattern exact role code, or {@code %} for any role
     */
    @Query(value = """
            select distinct u from User u left join u.roles r
            where (lower(u.username) like :pattern
                   or lower(u.fullName) like :pattern
                   or lower(u.email) like :pattern)
              and (:rolePattern = '%' or r.code like :rolePattern)
            """,
            countQuery = """
            select count(distinct u) from User u left join u.roles r
            where (lower(u.username) like :pattern
                   or lower(u.fullName) like :pattern
                   or lower(u.email) like :pattern)
              and (:rolePattern = '%' or r.code like :rolePattern)
            """)
    Page<User> search(@Param("pattern") String pattern, @Param("rolePattern") String rolePattern, Pageable pageable);

    /**
     * Stamps a successful sign-in and clears the failure state, as a single statement.
     *
     * <p>Deliberately not done by mutating the managed entity. {@code users} carries an
     * {@code @Version} column, so two sign-ins for the same account at the same moment - one
     * clinician on two workstations, a shared front-desk login, or any load test - both read
     * version N and both try to write version N+1. One wins; the other gets an
     * {@code ObjectOptimisticLockingFailureException} at commit and the caller sees a 500 on a
     * login that was, in fact, correct. A found-by-load-testing bug, and the reason this is a
     * bulk update: last-write-wins is the right semantics for a login timestamp, and a bulk
     * update leaves the version column alone rather than contending on it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u
               set u.lastLoginAt = :at, u.failedLoginAttempts = 0, u.lockedUntil = null
             where u.id = :id
            """)
    int markSuccessfulLogin(@Param("id") UUID id, @Param("at") java.time.Instant at);

    /**
     * Counts a failed sign-in and locks the account once the threshold is crossed, as a single
     * statement.
     *
     * <p>Read-modify-write on the entity had two faults, not one. The optimistic-lock collision
     * above turned concurrent bad passwords into 500s instead of 401s - but worse, parallel
     * attempts both read the same count and both wrote count+1, so a burst of guesses could be
     * counted once. Incrementing in SQL makes the counter exact under any concurrency, which is
     * the whole point of a brute-force threshold.
     *
     * @return rows affected: 0 if the user no longer exists
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u
               set u.failedLoginAttempts =
                       case when u.failedLoginAttempts + 1 >= :threshold then 0
                            else u.failedLoginAttempts + 1 end,
                   u.lockedUntil =
                       case when u.failedLoginAttempts + 1 >= :threshold then :lockUntil
                            else u.lockedUntil end
             where u.id = :id
            """)
    int recordFailedLogin(@Param("id") UUID id, @Param("threshold") int threshold,
                          @Param("lockUntil") java.time.Instant lockUntil);
}
