package com.dadp.wrapper.crypto;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalAesGcmCryptoTest {

    @Test
    void encryptDecryptUsesEngineCompatiblePolicyUidPrefix() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        KeyMaterial material = new KeyMaterial(
                new KeyMetadata("customer-key", 1, "HUB", null, null, "A256GCM"),
                Base64.getEncoder().encodeToString(key));
        LocalAesGcmCrypto crypto = new LocalAesGcmCrypto(new SecureRandom());

        String policyUid = "123e4567-e89b-12d3-a456-426614174000";
        String encrypted = crypto.encrypt("hello-wrapper-local-crypto", policyUid, material);

        assertEquals(true, encrypted.startsWith("hub:" + policyUid + ":"));
        assertEquals(policyUid, LocalAesGcmCrypto.extractPolicyUid(encrypted));
        assertEquals("hello-wrapper-local-crypto", crypto.decrypt(encrypted, material));
    }

    @Test
    void partialEncryptDecryptUsesSharedCoreFormat() {
        KeyMaterial material = materialWithAlgorithm("A256GCM");
        LocalAesGcmCrypto crypto = new LocalAesGcmCrypto(new SecureRandom());
        String policyUid = "123e4567-e89b-12d3-a456-426614174000";

        String encrypted = crypto.encrypt("1234567812345678", policyUid, material, true, 0, 3);

        assertEquals(true, encrypted.startsWith("123::ENC::hub:" + policyUid + ":"));
        assertEquals("1234567812345678", crypto.decrypt(encrypted, material, 0, 3));
    }

    @Test
    void encryptUsesRandomIv() {
        KeyMaterial material = materialWithAlgorithm("A256GCM");
        LocalAesGcmCrypto crypto = new LocalAesGcmCrypto(new SecureRandom());
        String policyUid = "123e4567-e89b-12d3-a456-426614174000";

        String first = crypto.encrypt("same", policyUid, material);
        String second = crypto.encrypt("same", policyUid, material);

        assertNotEquals(first, second);
    }

    @Test
    void unsupportedAlgorithmRequiresRemoteFallback() {
        LocalAesGcmCrypto crypto = new LocalAesGcmCrypto();
        UnsupportedCryptoMaterialException error = assertThrows(
                UnsupportedCryptoMaterialException.class,
                () -> crypto.encrypt("value", "123e4567-e89b-12d3-a456-426614174000", materialWithAlgorithm("FPE_FF1")));
        assertEquals("Algorithm requires remote fallback: FPE_FF1", error.getMessage());
    }

    private static KeyMaterial materialWithAlgorithm(String algorithm) {
        byte[] key = new byte[32];
        return new KeyMaterial(
                new KeyMetadata("key", 1, "HUB", null, null, algorithm),
                Base64.getEncoder().encodeToString(key));
    }
}
