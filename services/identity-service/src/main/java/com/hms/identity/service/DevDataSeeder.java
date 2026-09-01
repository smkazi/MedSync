package com.hms.identity.service;

import com.hms.identity.domain.Role;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import java.util.List;
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
 * {@code HMS_SEED_PASSWORD} and every seeded account is flagged must-change-password.
 */
@Component
@ConditionalOnProperty(name = "hms.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private record SeedUser(String username, String email, String fullName, Set<String> roles) {
    }

    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("admin", "admin@hms.local", "System Administrator", Set.of("ADMIN")),
            new SeedUser("dr.rao", "rao@hms.local", "Dr Anika Rao", Set.of("DOCTOR")),
            new SeedUser("nurse.iqbal", "iqbal@hms.local", "Sana Iqbal", Set.of("NURSE")),
            new SeedUser("reception", "reception@hms.local", "Front Desk", Set.of("RECEPTIONIST")),
            new SeedUser("lab.tech", "labtech@hms.local", "Ravi Menon", Set.of("LAB_TECH")),
            new SeedUser("dr.pathan", "pathan@hms.local", "Dr Imran Pathan", Set.of("PATHOLOGIST")));

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
        for (SeedUser seed : SEED_USERS) {
            if (users.existsByUsernameIgnoreCase(seed.username())) {
                continue;
            }
            User user = new User(seed.username(), seed.email(), hash, seed.fullName());
            Set<Role> resolved = roles.findByCodeIn(seed.roles());
            if (resolved.isEmpty()) {
                log.warn("Skipping seed user {}: roles {} not found", seed.username(), seed.roles());
                continue;
            }
            user.replaceRoles(resolved);
            user.setMustChangePassword(true);
            users.save(user);
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} user(s); all must change their password at first sign-in", created);
        }
    }
}
