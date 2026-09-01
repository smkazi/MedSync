package com.hms.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.domain.User;
import com.hms.identity.repo.RoleRepository;
import com.hms.identity.repo.UserRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the real HTTP surface of the auth flows against a real database, because the
 * behaviour that matters here (rotation, reuse detection, lockout) lives in the interaction
 * between transactions and thrown exceptions rather than in any single method.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    private static final String SEED_PASSWORD = "TestPassword!2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * Creates a throwaway account so a test that deliberately locks one out cannot leak that state
     * into another test, or inherit it from a previous run against the same database.
     */
    private String createDisposableUser() {
        String username = "temp-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User(username, username + "@hms.local", passwordEncoder.encode(SEED_PASSWORD),
                "Disposable Test User");
        user.replaceRoles(new java.util.LinkedHashSet<>(roles.findByCodeIn(Set.of("NURSE"))));
        users.save(user);
        return username;
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String refreshCall(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("valid credentials return an access token carrying the user's roles")
    void loginSucceeds() throws Exception {
        JsonNode body = login("admin", SEED_PASSWORD);

        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("accessToken").asString()).isNotBlank();
        assertThat(body.get("refreshToken").asString()).isNotBlank();
        assertThat(body.get("user").get("roles").toString()).contains("ADMIN");
    }

    @Test
    @DisplayName("a wrong password and an unknown username are indistinguishable")
    void failedLoginsAreIndistinguishable() throws Exception {
        String wrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "dr.rao", "password", "definitely-wrong"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "no-such-account", "password", "definitely-wrong"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(wrongPassword).get("detail").asString())
                .isEqualTo(objectMapper.readTree(unknownUser).get("detail").asString());
    }

    @Test
    @DisplayName("refreshing rotates the token and replaying the old one burns the whole family")
    void refreshRotatesAndDetectsReuse() throws Exception {
        JsonNode session = login(createDisposableUser(), SEED_PASSWORD);
        String original = session.get("refreshToken").asString();

        JsonNode rotated = objectMapper.readTree(refreshCall(original));
        String replacement = rotated.get("refreshToken").asString();
        assertThat(replacement).isNotEqualTo(original);

        // Replaying the consumed token is treated as theft. 401, not 400: the token is a
        // credential, and a client that gets a 400 has no reason to send the user back to sign in.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", original))))
                .andExpect(status().isUnauthorized());

        // ...which must also invalidate the token issued by the rotation.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", replacement))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/auth/me requires a token and returns the caller's own profile")
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());

        String accessToken = login("dr.pathan", SEED_PASSWORD).get("accessToken").asString();
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dr.pathan"))
                .andExpect(jsonPath("$.roles[0]").value("PATHOLOGIST"));
    }

    @Test
    @DisplayName("admin endpoints are closed to non-admin roles")
    void adminEndpointsEnforceRole() throws Exception {
        String doctorToken = login("dr.rao", SEED_PASSWORD).get("accessToken").asString();

        mockMvc.perform(get("/admin/users").header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());

        String adminToken = login("admin", SEED_PASSWORD).get("accessToken").asString();
        mockMvc.perform(get("/admin/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    @DisplayName("user search works unfiltered and narrows by term and by role")
    void userSearchFiltersWithoutNullParameters() throws Exception {
        String adminToken = "Bearer " + login("admin", SEED_PASSWORD).get("accessToken").asString();

        // Unfiltered: the regression case -- a nullable filter parameter used to reach PostgreSQL
        // untyped and fail with "function lower(bytea) does not exist".
        mockMvc.perform(get("/admin/users").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));

        mockMvc.perform(get("/admin/users").param("q", "rao").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("dr.rao"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // The regression this guards: the role predicate used to admit every user with NO roles,
        // because the left join leaves r.code null for them and the filter said "or r.code is
        // null". Asking for pathologists returned every role-less account too. Asserted with a
        // count rather than a first row, so a disposable account left behind by another test
        // cannot make it pass or fail for the wrong reason.
        mockMvc.perform(get("/admin/users").param("role", "PATHOLOGIST").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("dr.pathan"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/admin/users").param("q", "no-such-person").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("the audit trail is queryable unfiltered and by action")
    void auditTrailIsQueryable() throws Exception {
        String adminToken = "Bearer " + login("admin", SEED_PASSWORD).get("accessToken").asString();

        mockMvc.perform(get("/admin/audit").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/admin/audit").param("action", "LOGIN_SUCCEEDED").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_SUCCEEDED"));
    }

    @Test
    @DisplayName("JWKS is public and advertises an RS256 signing key")
    void jwksIsPubliclyAvailable() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").exists());
    }

    @Test
    @DisplayName("JWKS never exposes private key material")
    void jwksExcludesPrivateKeyMaterial() throws Exception {
        String jwks = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString();

        // "d", "p", "q" are the RSA private components; their presence would leak the signing key.
        assertThat(jwks).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
    }

    @Test
    @DisplayName("repeated failures lock the account, and the lock outranks correct credentials")
    void repeatedFailuresLockTheAccount() throws Exception {
        String username = createDisposableUser();
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", "wrong-" + attempt))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PASSWORD))))
                .andExpect(status().isLocked());
    }
}
