package com.hms.identity.service;

import com.hms.identity.domain.Role;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator and a demo user per role so a fresh database is usable
 * immediately. Enabled by {@code hms.seed.enabled} (on in the dev profile, off by default) and
 * idempotent: existing usernames are left untouched.
 *
 * <p>The seed password is deliberately configuration-driven; deployments set
 * {@code HMS_SEED_PASSWORD}.
 *
 * <p><strong>One</strong> seeded account carries the must-change-password flag, and it is there to
 * exercise the gate rather than to be worked in. Since a flagged account gets a role-less token and
 * so can reach nothing but {@code /auth/change-password}, flagging all of them — which is what this
 * used to do — would have left a freshly seeded platform with no usable account at all, and the
 * demo fixtures are the accounts every test and every walkthrough signs in as. The accounts an
 * administrator creates through {@code POST /users} are still flagged, which is the path that
 * matters outside a demo.
 */
@Component
@ConditionalOnProperty(name = "hms.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private record SeedUser(String id, String username, String email, String fullName, Set<String> roles,
                            boolean mustChangePassword) {

        SeedUser(String id, String username, String email, String fullName, Set<String> roles) {
            this(id, username, email, fullName, roles, false);
        }
    }

    /**
     * The demo accounts, with <strong>stable</strong> ids.
     *
     * <p>They were random UUIDs, and that broke a thing which is not obvious from here: an
     * appointment's {@code clinician_id} is a user id, and patient-service's staff directory is what
     * turns one into a name a receptionist can pick from a list. With random ids, no migration in
     * another service could reference these users, so no staff row could be seeded, so the clinician
     * dropdown on the booking and availability screens was empty on every fresh deployment — and
     * scheduling's own seeded weekly pattern pointed at a clinician who did not exist. Its comment
     * said "clinician ids are resolved by the caller"; nothing resolved them.
     *
     * <p>Stable ids make the demo graph joinable across services. The series is
     * {@code 33333333-0000-4000-8000-00000000000N}, alongside {@code 11111111-…} for roles and
     * {@code 22222222-…} for departments.
     *
     * <p>These are dev fixtures, so nothing here repairs a database that was already seeded with
     * random ids — the id is a primary key and cannot be rewritten under the rows that reference it.
     * A dev database from before this change needs its schemas dropped, which is what
     * {@code make dev-test-stack} does.
     */
    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("33333333-0000-4000-8000-000000000001", "admin", "admin@hms.local",
                    "System Administrator", Set.of("ADMIN")),
            new SeedUser("33333333-0000-4000-8000-000000000002", "dr.rao", "rao@hms.local",
                    "Dr Anika Rao", Set.of("DOCTOR")),
            new SeedUser("33333333-0000-4000-8000-000000000003", "nurse.iqbal", "iqbal@hms.local",
                    "Sana Iqbal", Set.of("NURSE")),
            new SeedUser("33333333-0000-4000-8000-000000000004", "reception", "reception@hms.local",
                    "Front Desk", Set.of("RECEPTIONIST")),
            new SeedUser("33333333-0000-4000-8000-000000000005", "lab.tech", "labtech@hms.local",
                    "Ravi Menon", Set.of("LAB_TECH")),
            new SeedUser("33333333-0000-4000-8000-000000000006", "dr.pathan", "pathan@hms.local",
                    "Dr Imran Pathan", Set.of("PATHOLOGIST")),
            // The fixture for the initial-password gate: signs in, holds a real role in the
            // database, and can still do nothing until the password is changed.
            new SeedUser("33333333-0000-4000-8000-000000000007", "new.starter", "starter@hms.local",
                    "Priya Nair", Set.of("RECEPTIONIST"), true));

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public DevDataSeeder(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                         @Value("${hms.seed.password:ChangeMe!Dev2026}") String seedPassword) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String hash = passwordEncoder.encode(seedPassword);
        int created = 0;
        int realigned = 0;
        for (SeedUser seed : SEED_USERS) {
            Optional<User> existing = users.findByUsernameIgnoreCase(seed.username());
            if (existing.isPresent()) {
                // Only the flag is realigned, and only for accounts this class defines. A dev
                // database seeded before the gate existed has all six fixtures flagged, which now
                // means six accounts that can sign in and do nothing — a platform that looks
                // broken for a reason nobody would guess. Passwords, roles and ids are left alone:
                // this repairs the seeder's own drift, it does not administer the database.
                User user = existing.get();
                if (user.isMustChangePassword() != seed.mustChangePassword()) {
                    user.setMustChangePassword(seed.mustChangePassword());
                    realigned++;
                }
                continue;
            }
            User user = new User(seed.username(), seed.email(), hash, seed.fullName());
            user.setId(java.util.UUID.fromString(seed.id()));
            Set<Role> resolved = roles.findByCodeIn(seed.roles());
            if (resolved.isEmpty()) {
                log.warn("Skipping seed user {}: roles {} not found", seed.username(), seed.roles());
                continue;
            }
            user.replaceRoles(resolved);
            user.setMustChangePassword(seed.mustChangePassword());
            users.save(user);
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} user(s)", created);
        }
        if (realigned > 0) {
            log.info("Realigned the must-change-password flag on {} existing seed account(s)", realigned);
        }
    }
}
