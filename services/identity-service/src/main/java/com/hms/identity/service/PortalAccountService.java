package com.hms.identity.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.Roles;
import com.hms.identity.domain.Role;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import com.hms.identity.web.dto.AuthDtos;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal accounts: one per patient, issued at the desk, and administered nowhere else.
 *
 * <p>Deliberately separate from {@link UserAdminService} even though both write to {@code users}.
 * A staff account is created by choosing a username, an address and a set of roles; a portal
 * account has no choices in it at all — the username is the patient's MRN, the address is the one
 * on their record, and the role is always and only PATIENT. Routing enrolment through the staff
 * endpoint would have meant a request body that could name any role, on a path a receptionist can
 * reach, which is one typo away from a patient with a clinician's authority.
 *
 * <p>The two invariants this class exists to hold, neither of which a constraint can:
 * <ul>
 *   <li><strong>The link and the role are written together.</strong> An account with a patient id
 *       and no PATIENT role holds no authority; one with the role and no link is refused by every
 *       portal endpoint. Both are harmless, and both are also nonsense, so neither is created.</li>
 *   <li><strong>A patient id is never moved.</strong> {@code User.patientId} has no setter.
 *       Re-pointing a live account would hand its new owner the previous patient's secure-messaging
 *       history, and neither person would see anything change. Re-issuing access resets a password;
 *       giving access to somebody else is a different account.</li>
 * </ul>
 */
@Service
public class PortalAccountService {

    /**
     * A one-time password long enough to be worth the inconvenience of reading it out.
     *
     * <p>Twenty characters from a 32-symbol alphabet is 100 bits, which is far beyond anything the
     * five-attempt lockout would let an attacker reach — the length is not for that. It is because
     * this password travels by being spoken across a desk or printed on a slip, and the account it
     * opens is a medical record. It is valid until first use and the account cannot do anything
     * until it is changed.
     */
    private static final int PASSWORD_SYMBOLS = 20;

    /**
     * Crockford's base32 without I, L, O and U.
     *
     * <p>Removed because this is read aloud and copied by hand: 1/I/l and 0/O are the two mistakes
     * everybody makes, and U is out because dropping it is what keeps an accidental word from
     * appearing in a string somebody has to read to a stranger.
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuditService audit;

    public PortalAccountService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                                TokenService tokens, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
    }

    /**
     * Issues portal access for a patient, or re-issues it for one already enrolled.
     *
     * <p>One endpoint for both because from the desk they are one act — "this person cannot get in,
     * sort it out" — and because a separate reset path would have to answer what to do when it is
     * called for somebody who was never enrolled. Re-issuing revokes every live session: the reason
     * somebody is standing at the desk asking for a new password is often that the old one is known
     * to somebody else.
     */
    @Transactional
    public AuthDtos.PortalAccountIssued enrol(AuthDtos.EnrolPortalAccountRequest request) {
        String password = temporaryPassword();
        String email = request.email().toLowerCase(Locale.ROOT);
        String username = request.mrn().toLowerCase(Locale.ROOT);

        User existing = users.findByPatientId(request.patientId()).orElse(null);
        if (existing != null) {
            existing.changePassword(passwordEncoder.encode(password));
            existing.setMustChangePassword(true);
            // Reactivating is the point of re-issuing: an account disabled after a lost phone is
            // exactly the account somebody comes back to the desk about.
            existing.setActive(true);
            existing.setEmail(email);
            existing.setFullName(request.fullName());
            int revoked = tokens.revokeAllForUser(existing.getId(), "portal-access-reissued");
            audit.record("PORTAL_ACCESS_REISSUED", "Patient", request.patientId(),
                    revoked + " session(s) revoked");
            return new AuthDtos.PortalAccountIssued(
                    request.patientId(), existing.getUsername(), password, Instant.now());
        }

        // The MRN is the username because it is the one identifier the patient already has, is
        // already printed on everything they have been given, and cannot collide with a staff
        // username — those are names, these are MRNs. The check is still made, because "cannot
        // collide" is a statement about a naming convention and conventions get changed.
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException(
                    "Username '" + username + "' is already taken, so this MRN cannot be used for "
                            + "portal access. This should not happen and is worth reporting.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(
                    "The address " + email + " already has an account on this platform. A portal "
                            + "account needs an address of its own — two people sharing one inbox "
                            + "would each be able to reset the other's access to their record.");
        }

        User user = new User(username, email, passwordEncoder.encode(password),
                request.fullName(), request.patientId());
        user.replaceRoles(Set.of(patientRole()));
        // The desk chose nothing here and still knows the password, so the same rule staff accounts
        // follow applies: the account owes a change before its token carries any role at all.
        user.setMustChangePassword(true);
        users.save(user);

        // The patient id, never the name or the address: an audit line is read by more people than
        // the record is, and this one only has to say that access to a numbered record was issued.
        audit.record("PORTAL_ACCESS_ISSUED", "Patient", request.patientId(), "portal account created");
        return new AuthDtos.PortalAccountIssued(request.patientId(), username, password, Instant.now());
    }

    @Transactional(readOnly = true)
    public AuthDtos.PortalAccountResponse find(UUID patientId) {
        return users.findByPatientId(patientId).map(PortalAccountService::toResponse)
                .orElseThrow(() -> new NotFoundException(
                        "This patient has no portal access. The front desk can issue it."));
    }

    /**
     * Takes portal access away, immediately.
     *
     * <p>Deactivates rather than deletes, and revokes every live session rather than waiting for a
     * token to expire. Deleting would take the account's own audit trail with it, and an access
     * token minted a minute ago is good for its whole lifetime unless something ends it — which is
     * the entire difference between "we disabled it" and "we disabled it in fifteen minutes' time".
     */
    @Transactional
    public AuthDtos.PortalAccountResponse deactivate(UUID patientId) {
        User user = users.findByPatientId(patientId).orElseThrow(() -> new NotFoundException(
                "This patient has no portal access, so there is nothing to withdraw."));
        user.setActive(false);
        int revoked = tokens.revokeAllForUser(user.getId(), "portal-access-withdrawn");
        audit.record("PORTAL_ACCESS_WITHDRAWN", "Patient", patientId, revoked + " session(s) revoked");
        return toResponse(user);
    }

    private Role patientRole() {
        return roles.findByCodeIn(Set.of(Roles.PATIENT)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The PATIENT role is missing. It is created by a migration, so this means "
                                + "the identity schema is behind."));
    }

    private static AuthDtos.PortalAccountResponse toResponse(User user) {
        return new AuthDtos.PortalAccountResponse(user.getPatientId(), user.getUsername(),
                user.getEmail(), user.isActive(), user.isMustChangePassword(), user.getLastLoginAt());
    }

    private static String temporaryPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_SYMBOLS);
        for (int i = 0; i < PASSWORD_SYMBOLS; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
