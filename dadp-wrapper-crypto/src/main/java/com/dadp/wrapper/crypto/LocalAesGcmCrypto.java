package com.dadp.wrapper.crypto;

import com.dadp.crypto.core.CryptoMaterial;
import com.dadp.crypto.core.DadpCryptoCore;

import java.security.SecureRandom;

/**
 * Wrapper adapter for the shared engine-compatible crypto core.
 */
public final class LocalAesGcmCrypto {

    private final DadpCryptoCore core;

    public LocalAesGcmCrypto() {
        this(new SecureRandom());
    }

    LocalAesGcmCrypto(SecureRandom secureRandom) {
        this.core = new DadpCryptoCore(secureRandom);
    }

    public String encrypt(String plainText, String policyUid, KeyMaterial keyMaterial) {
        return encrypt(plainText, policyUid, keyMaterial, null, null, null);
    }

    public String encrypt(String plainText, String policyUid, KeyMaterial keyMaterial,
                          Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (plainText == null) {
            return null;
        }
        try {
            return core.encrypt(plainText, toMaterial(policyUid, keyMaterial, usePlain, plainStart, plainLength));
        } catch (com.dadp.crypto.core.UnsupportedCryptoMaterialException e) {
            throw new UnsupportedCryptoMaterialException(e.getMessage());
        } catch (com.dadp.crypto.core.CoreCryptoException e) {
            throw new WrapperCryptoException(e.getMessage(), e);
        }
    }

    public String decrypt(String encryptedData, KeyMaterial keyMaterial) {
        return decrypt(encryptedData, keyMaterial, null, null);
    }

    public String decrypt(String encryptedData, KeyMaterial keyMaterial, Integer plainStart, Integer plainLength) {
        if (encryptedData == null) {
            return null;
        }
        try {
            String policyUid = DadpCryptoCore.extractPolicyUid(encryptedData);
            return core.decrypt(encryptedData, toMaterial(policyUid, keyMaterial, null, plainStart, plainLength));
        } catch (com.dadp.crypto.core.UnsupportedCryptoMaterialException e) {
            throw new UnsupportedCryptoMaterialException(e.getMessage());
        } catch (com.dadp.crypto.core.CoreCryptoException e) {
            throw new WrapperCryptoException(e.getMessage(), e);
        }
    }

    public static String extractPolicyUid(String encryptedData) {
        return DadpCryptoCore.extractPolicyUid(encryptedData);
    }

    private static CryptoMaterial toMaterial(String policyUid, KeyMaterial keyMaterial,
                                             Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (keyMaterial == null) {
            throw new IllegalArgumentException("key material is required");
        }
        KeyMetadata metadata = keyMaterial.getMetadata();
        return new CryptoMaterial(
                policyUid,
                metadata.getKeyAlias(),
                metadata.getKeyVersion(),
                metadata.getProvider(),
                metadata.getAlgorithm(),
                keyMaterial.getKeyData(),
                usePlain,
                plainStart,
                plainLength);
    }
}
