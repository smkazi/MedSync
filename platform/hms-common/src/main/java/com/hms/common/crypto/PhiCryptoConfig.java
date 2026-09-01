package com.hms.common.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the PHI cipher from configuration before any entity is read or written.
 *
 * <p>A deployment must supply {@code hms.crypto.phi-key}. For local development a key is derived
 * from a fixed dev seed so a checkout works immediately — with a loud warning, because that key is
 * in the source tree and protects nothing.
 */
@Configuration
public class PhiCryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(PhiCryptoConfig.class);

    /** Base64 of 32 zero bytes: unmistakably a development value. */
    private static final String DEV_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final String configuredKey;

    public PhiCryptoConfig(@Value("${hms.crypto.phi-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void installCipher() {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("hms.crypto.phi-key is not set - falling back to the built-in DEVELOPMENT key. "
                    + "Encrypted patient fields are NOT protected. Set HMS_PHI_KEY in any real environment.");
            EncryptedStringConverter.install(new PhiCipher(DEV_KEY));
            return;
        }
        EncryptedStringConverter.install(new PhiCipher(configuredKey));
        log.info("PHI column encryption enabled (AES-256-GCM)");
    }
}
