package com.hms.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for the patient identifiers that must be stored but never need to be
 * searched (national id, insurance policy number).
 *
 * <p>GCM is authenticated, so a tampered ciphertext fails to decrypt rather than yielding garbage.
 * Each value gets a fresh 12-byte nonce, prepended to the ciphertext, and the whole thing is
 * Base64-encoded for a plain {@code varchar} column. Values are versioned with a {@code v1:} prefix
 * so a future key or algorithm change can be rolled out without a big-bang migration.
 */
public final class PhiCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * @param base64Key a Base64-encoded 256-bit key
     * @throws IllegalArgumentException if the key is not 32 bytes once decoded
     */
    public PhiCipher(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalArgumentException(
                    "PHI encryption key must be 32 bytes (256 bits) Base64-encoded, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt PHI value", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Plaintext left by an import or a pre-encryption row: return as-is rather than failing
            // a whole patient read, and let a migration job re-encrypt it.
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, NONCE_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Could not decrypt PHI value (wrong key or tampered data)", ex);
        }
    }

    /** Generates a fresh Base64 key, for {@code scripts/gen-keys.sh} and tests. */
    public static String generateKeyBase64() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }
}
