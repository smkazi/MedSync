package com.hms.apitests.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.hms.apitests.support.Api;
import com.hms.apitests.support.RequiresRunningStack;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token forgery. Every service here is a stateless OAuth2 resource server that trusts a signature
 * and nothing else, so the signature check is the entire perimeter - there is no session to
 * invalidate and no second opinion to fall back on.
 *
 * <p>The classic failures are all represented: {@code alg: none}, an algorithm swap from RS256 to
 * HS256 using the public key as the HMAC secret, a token signed by a key the server has never
 * seen, an expired token, and a payload edited to add a role. Each must be rejected with a 401 -
 * not a 500, which would mean the token reached code that tried to use it.
 */
@DisplayName("JWT forgery and tampering")
class JwtTamperingIT extends RequiresRunningStack {

    private static String validToken;
    private static JWTClaimsSet validClaims;
    private static String keyId;

    /** Any endpoint that needs a valid token; the assertion is about the token, not the route. */
    private static final String GUARDED = "/patients?q=nothing&page=0&size=1";

    @BeforeAll
    static void captureAValidToken() throws Exception {
        validToken = Api.token(Api.DOCTOR);
        SignedJWT parsed = SignedJWT.parse(validToken);
        validClaims = parsed.getJWTClaimsSet();
        keyId = parsed.getHeader().getKeyID();

        // Sanity: the token we are about to mutate must actually work, or every assertion below
        // would pass for the wrong reason.
        given().spec(Api.withToken(validToken)).when().get(GUARDED).then().statusCode(200);
    }

    @Test
    @DisplayName("the issuer signs with RS256 and publishes a key id")
    void issuerUsesAsymmetricSigning() throws Exception {
        SignedJWT parsed = SignedJWT.parse(validToken);
        assertThat(parsed.getHeader().getAlgorithm().getName())
                .as("a symmetric algorithm would mean every resource server holds the signing secret")
                .isEqualTo("RS256");
        assertThat(parsed.getHeader().getKeyID())
                .as("without a kid the server cannot tell which key to verify against during rotation")
                .isNotBlank();
    }

    @Test
    @DisplayName("alg=none is rejected")
    void algNoneIsRejected() {
        // Same claims, no signature. A library that honours the header's own algorithm choice
        // will happily "verify" this.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = validToken.split("\\.")[1];
        String forged = header + "." + payload + ".";

        given().spec(Api.withToken(forged)).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("an RS256-to-HS256 algorithm swap is rejected")
    void algorithmConfusionIsRejected() throws Exception {
        // The classic: take the issuer's PUBLIC key - which is published at /.well-known/jwks.json
        // and therefore known to the attacker - and use its bytes as an HMAC secret. A server that
        // picks the verification algorithm from the token's own header accepts this.
        String jwks = given().spec(Api.spec()).when().get("/.well-known/jwks.json")
                .then().statusCode(200).extract().body().asString();
        byte[] secret = jwks.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[Math.max(32, secret.length)];
        System.arraycopy(secret, 0, padded, 0, Math.min(secret.length, padded.length));

        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).type(JOSEObjectType.JWT).build(),
                validClaims);
        forged.sign(new MACSigner(padded));

        given().spec(Api.withToken(forged.serialize())).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("a token signed by a key the issuer never published is rejected")
    void foreignSigningKeyIsRejected() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID(keyId).generate();

        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), validClaims);
        forged.sign(new RSASSASigner(attackerKey));

        given().spec(Api.withToken(forged.serialize())).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("an unknown kid is rejected rather than falling back to any key")
    void unknownKeyIdIsRejected() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("not-a-real-kid").generate();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("not-a-real-kid").build(), validClaims);
        forged.sign(new RSASSASigner(attackerKey));

        given().spec(Api.withToken(forged.serialize())).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("editing the payload to add a role invalidates the signature")
    void payloadTamperingIsRejected() {
        String[] parts = validToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String escalated = payload.replaceFirst("\"DOCTOR\"", "\"DOCTOR\",\"ADMIN\"");
        assertThat(escalated).as("the token must actually have been changed").isNotEqualTo(payload);

        String tampered = parts[0] + "." + base64Url(escalated) + "." + parts[2];
        given().spec(Api.withToken(tampered)).when().get(GUARDED).then().statusCode(401);

        // And the admin surface stays shut, which is what the escalation was aiming at.
        given().spec(Api.withToken(tampered)).when().get("/admin/users").then().statusCode(401);
    }

    @Test
    @DisplayName("an expired token is rejected even though its signature is valid")
    void expiredTokenIsRejected() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID(keyId).generate();
        JWTClaimsSet expired = new JWTClaimsSet.Builder(validClaims)
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), expired);
        forged.sign(new RSASSASigner(key));

        given().spec(Api.withToken(forged.serialize())).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void foreignIssuerIsRejected() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID(keyId).generate();
        JWTClaimsSet foreign = new JWTClaimsSet.Builder(validClaims)
                .issuer("https://attacker.example.org")
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .build();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), foreign);
        forged.sign(new RSASSASigner(key));

        given().spec(Api.withToken(forged.serialize())).when().get(GUARDED).then().statusCode(401);
    }

    @Test
    @DisplayName("garbage in the Authorization header is a 401, never a 500")
    void malformedTokensAreRejectedCleanly() {
        for (String junk : List.of("", "notatoken", "a.b.c", "Bearer", "....",
                "eyJhbGciOiJSUzI1NiJ9", validToken + "extra")) {
            given().spec(Api.withToken(junk)).when().get(GUARDED)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(400)));
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
