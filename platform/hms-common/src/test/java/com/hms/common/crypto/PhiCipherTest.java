package com.hms.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhiCipherTest {

    private final PhiCipher cipher = new PhiCipher(PhiCipher.generateKeyBase64());

    @Test
    void roundTripsAValue() {
        String plaintext = "ABCDE1234F";
        assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        assertThat(cipher.encrypt("ABCDE1234F")).doesNotContain("ABCDE1234F");
    }

    @Test
    void encryptingTheSameValueTwiceGivesDifferentCiphertext() {
        // A fresh nonce per value: identical national ids must not be linkable by their ciphertext.
        assertThat(cipher.encrypt("SAME-VALUE")).isNotEqualTo(cipher.encrypt("SAME-VALUE"));
    }

    @Test
    void nullPassesThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void ciphertextIsVersionTagged() {
        assertThat(cipher.encrypt("x")).startsWith("v1:");
    }

    @Test
    void plaintextLeftByAnImportIsReturnedUnchanged() {
        // Rows written before encryption was switched on must not break a whole patient read.
        assertThat(cipher.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext");
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanDecryptedToGarbage() {
        String encrypted = cipher.encrypt("ABCDE1234F");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampered");
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("ABCDE1234F");
        PhiCipher otherKey = new PhiCipher(PhiCipher.generateKeyBase64());

        assertThatThrownBy(() -> otherKey.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe256Bits() {
        assertThatThrownBy(() -> new PhiCipher(java.util.Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
