package com.hms.identity.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** An RSA keypair for signing access tokens. Retired keys stay published in JWKS until their tokens expire. */
@Entity
@Table(name = "signing_keys")
public class SigningKey extends BaseEntity {

    @Column(name = "kid", nullable = false, unique = true, length = 64)
    private String kid;

    @Column(name = "public_pem", nullable = false, columnDefinition = "text")
    private String publicPem;

    @Column(name = "private_pem", nullable = false, columnDefinition = "text")
    private String privatePem;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected SigningKey() {
    }

    public SigningKey(String kid, String publicPem, String privatePem) {
        this.kid = kid;
        this.publicPem = publicPem;
        this.privatePem = privatePem;
    }

    public String getKid() {
        return kid;
    }

    public String getPublicPem() {
        return publicPem;
    }

    public String getPrivatePem() {
        return privatePem;
    }

    public boolean isActive() {
        return active;
    }

    public void retire() {
        this.active = false;
        this.retiredAt = Instant.now();
    }
}
