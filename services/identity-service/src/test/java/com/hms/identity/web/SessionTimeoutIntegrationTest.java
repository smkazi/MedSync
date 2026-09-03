package com.hms.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.domain.RefreshToken;
import com.hms.identity.domain.User;
import com.hms.identity.repo.RefreshTokenRepository;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two bounds on a session's life, each proved by binding it to zero rather than by sleeping.
 *
 * <p>A wall-clock test of a thirty-minute idle timeout would take thirty minutes or lie about it
 * with a mocked clock; binding the bound itself to {@code PT0S} asserts the rule — that the
 * elapsed time is measured against the configured limit and the family is burned when it is
 * exceeded — which is the part that can be wrong. The production values are exercised by
 * {@code AuthFlowIntegrationTest.refreshRotatesAndDetectsReuse}, where a rotation seconds after
 * sign-in must and does succeed.
 *
 * <p>Two contexts because the idle bound is checked first: with both at zero the absolute bound
 * would never be reached, and a test that cannot fail is not a test.
 */
class SessionTimeoutIntegrationTest {

    private static final String SEED_PASSWORD = "TestPassword!2026";

    /** Shared plumbing; the bounds themselves come from each nested class's property overrides. */
    abstract static class SessionCase {

        @Autowired
        protected MockMvc mockMvc;

        @Autowired
        protected ObjectMapper objectMapper;

        @Autowired
        protected UserRepository users;

        @Autowired
        protected RoleRepository roles;

        @Autowired
        protected RefreshTokenRepository refreshTokens;

        @Autowired
        protected PasswordEncoder passwordEncoder;

        protected String disposableUser() {
            String username = "session-" + UUID.randomUUID().toString().substring(0, 8);
            User user = new User(username, username + "@hms.local", passwordEncoder.encode(SEED_PASSWORD),
                    "Session Bounds Test User");
            user.replaceRoles(new LinkedHashSet<>(roles.findByCodeIn(Set.of("NURSE"))));
            users.save(user);
            return username;
        }

        protected String signIn(String username) throws Exception {
            MvcResult result = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", SEED_PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("refreshToken").asString();
        }

        protected JsonNode refuseRefresh(String refreshToken) throws Exception {
            MvcResult result = mockMvc.perform(post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "hms.jwt.idle-timeout=PT0S",
            "hms.jwt.portal-idle-timeout=PT0S",
            "hms.jwt.session-max-lifetime=P7D"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("idle timeout")
    class Idle extends SessionCase {

        @Test
        @DisplayName("a session idle past its limit is refused as a timeout, not as a bad password")
        void idleSessionIsRefusedInItsOwnWords() throws Exception {
            JsonNode refusal = refuseRefresh(signIn(disposableUser()));

            // The whole reason SessionExpiredException exists. Flattened to BadCredentials, this
            // would read "Invalid username or password" and send the user to reset a password that
            // is perfectly fine.
            assertThat(refusal.get("title").asString()).isEqualTo("Session Expired");
            assertThat(refusal.get("detail").asString())
                    .contains("timed out")
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("a timeout revokes the session rather than just refusing the one call")
        void timeoutRevokesTheFamily() throws Exception {
            String username = disposableUser();
            String refreshToken = signIn(username);
            UUID userId = users.findByUsernameIgnoreCase(username).orElseThrow().getId();
            assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);

            refuseRefresh(refreshToken);

            // The point of the bound is to end the session, not to make the client ask again with
            // the same token. Nothing usable may be left behind.
            assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
            // ...and revoked for the reason that actually happened, so the audit trail does not
            // report an idle session as a rotation or as theft.
            assertThat(refreshTokens.findAll().stream()
                    .filter(token -> userId.equals(token.getUserId()))
                    .map(RefreshToken::getRevokedReason))
                    .containsOnly("idle-timeout");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            // Negative disables the idle bound, so the absolute one is the only thing that can fire.
            "hms.jwt.idle-timeout=PT-1S",
            "hms.jwt.portal-idle-timeout=PT-1S",
            "hms.jwt.session-max-lifetime=PT0S"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("absolute session lifetime")
    class Absolute extends SessionCase {

        @Test
        @DisplayName("an active session still ends at the absolute lifetime")
        void activeSessionsStillEnd() throws Exception {
            JsonNode refusal = refuseRefresh(signIn(disposableUser()));

            assertThat(refusal.get("title").asString()).isEqualTo("Session Expired");
            // Worth saying in its own words: the user did nothing wrong and was not idle.
            assertThat(refusal.get("detail").asString()).contains("however active they are");
        }
    }
}
