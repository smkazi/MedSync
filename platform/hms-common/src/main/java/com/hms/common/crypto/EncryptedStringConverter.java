package com.hms.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts a String column with {@link PhiCipher}.
 *
 * <p>Hibernate instantiates converters itself, so the cipher is held statically and installed by
 * {@link PhiCryptoConfig} during startup. If no key is configured the converter refuses to run
 * rather than silently writing plaintext PHI.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static volatile PhiCipher cipher;

    static void install(PhiCipher installed) {
        cipher = installed;
    }

    private static PhiCipher require() {
        PhiCipher current = cipher;
        if (current == null) {
            throw new IllegalStateException(
                    "PHI encryption key is not configured; set hms.crypto.phi-key (HMS_PHI_KEY)");
        }
        return current;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : require().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : require().decrypt(dbData);
    }
}
