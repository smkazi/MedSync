package com.hms.identity.service;

import com.hms.identity.domain.SigningKey;
import com.hms.identity.repo.SigningKeyRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the RSA keypairs that sign access tokens.
 *
 * <p>Keys come from configuration when supplied ({@code hms.jwt.private-key} /
 * {@code hms.jwt.public-key}, PEM, injected from a secret manager in production) and are otherwise
 * generated on first boot and persisted, so a fresh developer checkout works with no key ceremony.
 * Only the public half is ever published, via {@code /.well-known/jwks.json}.
 */
@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);
    private static final int KEY_SIZE = 2048;

    private final SigningKeyRepository repository;
    private final String configuredPrivatePem;
    private final String configuredPublicPem;

    private volatile RSAKey activeKey;
    private volatile JWKSet publicJwkSet;

    public KeyService(SigningKeyRepository repository,
                      @Value("${hms.jwt.private-key:}") String configuredPrivatePem,
                      @Value("${hms.jwt.public-key:}") String configuredPublicPem) {
        this.repository = repository;
        this.configuredPrivatePem = configuredPrivatePem;
        this.configuredPublicPem = configuredPublicPem;
    }

    @PostConstruct
    @Transactional
    public void initialise() {
        SigningKey stored = repository.findFirstByActiveTrueOrderByCreatedAtDesc().orElse(null);
        if (stored == null) {
            stored = repository.save(createKeyRecord());
            log.info("Generated signing key {} for access tokens", stored.getKid());
        }
        reload();
    }

    /** Rebuilds the in-memory signing key and the published JWKS from the database. */
    @Transactional(readOnly = true)
    public void reload() {
        SigningKey active = repository.findFirstByActiveTrueOrderByCreatedAtDesc()
                .orElseThrow(() -> new IllegalStateException("No active signing key"));
        this.activeKey = toRsaKey(active, true);
        List<RSAKey> published = repository.findAllByOrderByCreatedAtDesc().stream()
                .map(key -> toRsaKey(key, false))
                .toList();
        this.publicJwkSet = new JWKSet(List.copyOf(published));
    }

    /** The key currently used to sign, private half included. */
    public RSAKey activeKey() {
        return activeKey;
    }

    /** Every key a verifier should accept, public halves only — this is the JWKS document. */
    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }

    private SigningKey createKeyRecord() {
        if (!configuredPrivatePem.isBlank() && !configuredPublicPem.isBlank()) {
            String kid = "cfg-" + UUID.randomUUID().toString().substring(0, 8);
            return new SigningKey(kid, normalisePem(configuredPublicPem), normalisePem(configuredPrivatePem));
        }
        KeyPair pair = generateKeyPair();
        String kid = "gen-" + UUID.randomUUID().toString().substring(0, 8);
        return new SigningKey(kid, toPem("PUBLIC KEY", pair.getPublic().getEncoded()),
                toPem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA unavailable in this JVM", ex);
        }
    }

    private RSAKey toRsaKey(SigningKey record, boolean includePrivate) {
        RSAPublicKey publicKey = readPublicKey(record.getPublicPem());
        RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                .keyID(record.getKid())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256);
        if (includePrivate) {
            builder.privateKey(readPrivateKey(record.getPrivatePem()));
        }
        return builder.build();
    }

    private RSAPublicKey readPublicKey(String pem) {
        try {
            byte[] der = decodePem(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("Invalid RSA public key PEM", ex);
        }
    }

    private RSAPrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = decodePem(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("Invalid RSA private key PEM (PKCS#8 expected)", ex);
        }
    }

    private static byte[] decodePem(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String toPem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    /** Accepts a PEM given on one line (as env vars often are) by restoring its line breaks. */
    private static String normalisePem(String pem) {
        return pem.replace("\\n", "\n").trim() + "\n";
    }
}
