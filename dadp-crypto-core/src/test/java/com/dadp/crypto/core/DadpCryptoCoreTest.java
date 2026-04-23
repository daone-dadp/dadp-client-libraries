package com.dadp.crypto.core;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DadpCryptoCoreTest {

    @Test
    void encryptDecryptUsesEngineHubFormat() {
        DadpCryptoCore crypto = new DadpCryptoCore(new SecureRandom());
        CryptoMaterial material = material("HUB", "A256GCM", false, null, null);

        String encrypted = crypto.encrypt("hello-core", material);

        assertTrue(encrypted.startsWith("hub:123e4567-e89b-12d3-a456-426614174000:"));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", DadpCryptoCore.extractPolicyUid(encrypted));
        assertEquals("hello-core", crypto.decrypt(encrypted, material));
    }

    @Test
    void partialEncryptDecryptKeepsPlainSegmentInEngineFormat() {
        DadpCryptoCore crypto = new DadpCryptoCore(new SecureRandom());
        CryptoMaterial material = material("HUB", "A256GCM", true, 0, 3);

        String encrypted = crypto.encrypt("1234567812345678", material);

        assertTrue(encrypted.startsWith("123::ENC::hub:123e4567-e89b-12d3-a456-426614174000:"));
        assertEquals("1234567812345678", crypto.decrypt(encrypted, material));
    }

    @Test
    void encryptUsesRandomIv() {
        DadpCryptoCore crypto = new DadpCryptoCore(new SecureRandom());
        CryptoMaterial material = material("DB", "AES-256-GCM", false, null, null);

        String first = crypto.encrypt("same", material);
        String second = crypto.encrypt("same", material);

        assertNotEquals(first, second);
    }

    @Test
    void unsupportedProviderRequiresRemoteFallback() {
        DadpCryptoCore crypto = new DadpCryptoCore();

        UnsupportedCryptoMaterialException error = assertThrows(
                UnsupportedCryptoMaterialException.class,
                () -> crypto.encrypt("value", material("DADP_VAULT", "A256GCM", false, null, null)));

        assertEquals("Provider requires remote fallback: DADP_VAULT", error.getMessage());
    }

    @Test
    void unsupportedAlgorithmRequiresRemoteFallback() {
        DadpCryptoCore crypto = new DadpCryptoCore();

        UnsupportedCryptoMaterialException error = assertThrows(
                UnsupportedCryptoMaterialException.class,
                () -> crypto.encrypt("value", material("HUB", "FPE_FF1", false, null, null)));

        assertEquals("Algorithm requires remote fallback: FPE_FF1", error.getMessage());
    }

    private static CryptoMaterial material(String provider, String algorithm, Boolean usePlain,
                                           Integer plainStart, Integer plainLength) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return new CryptoMaterial(
                "123e4567-e89b-12d3-a456-426614174000",
                "customer-key",
                1,
                provider,
                algorithm,
                Base64.getEncoder().encodeToString(key),
                usePlain,
                plainStart,
                plainLength);
    }
}
