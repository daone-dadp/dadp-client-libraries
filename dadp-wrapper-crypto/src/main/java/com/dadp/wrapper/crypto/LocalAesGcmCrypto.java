package com.dadp.wrapper.crypto;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.crypto.core.CryptoMaterial;
import com.dadp.crypto.core.DadpCryptoCore;

import java.security.SecureRandom;

/**
 * Wrapper adapter for the shared engine-compatible crypto core.
 */
public final class LocalAesGcmCrypto {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(LocalAesGcmCrypto.class);

    private final DadpCryptoCore core;

    public LocalAesGcmCrypto() {
        this(new SecureRandom());
    }

    LocalAesGcmCrypto(SecureRandom secureRandom) {
        this.core = new DadpCryptoCore(secureRandom);
    }

    public String encrypt(String plainText, String policyUid, KeyMaterial keyMaterial) {
        return encrypt(plainText, policyUid, null, keyMaterial, null, null, null);
    }

    public String encrypt(String plainText, String policyUid, KeyMaterial keyMaterial,
                          Boolean usePlain, Integer plainStart, Integer plainLength) {
        return encrypt(plainText, policyUid, null, keyMaterial, usePlain, plainStart, plainLength);
    }

    public String encrypt(String plainText, String policyUid, String policyAlgorithm, KeyMaterial keyMaterial,
                          Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (plainText == null) {
            return null;
        }
        try {
            CryptoMaterial material = toMaterial(policyUid, policyAlgorithm, keyMaterial, usePlain, plainStart, plainLength);
            log.trace("Local core encrypt start: policyUid={}, algorithm={}, keyAlias={}, keyVersion={}, plainLength={}, usePlain={}, plainStart={}, plainLengthSegment={}",
                    material.getPolicyUid(), material.getAlgorithm(), material.getKeyAlias(), material.getKeyVersion(),
                    plainText.length(), material.getUsePlain(), material.getPlainStart(), material.getPlainLength());
            String encrypted = core.encrypt(plainText, material);
            log.trace("Local core encrypt completed: policyUid={}, encryptedLength={}, encryptedPrefix={}",
                    material.getPolicyUid(), encrypted != null ? encrypted.length() : 0, WrapperLocalCryptoDebug.preview(encrypted));
            return encrypted;
        } catch (com.dadp.crypto.core.UnsupportedCryptoMaterialException e) {
            throw new UnsupportedCryptoMaterialException(e.getMessage());
        } catch (com.dadp.crypto.core.CoreCryptoException e) {
            throw new WrapperCryptoException(e.getMessage(), e);
        }
    }

    public String decrypt(String encryptedData, KeyMaterial keyMaterial) {
        return decrypt(encryptedData, null, keyMaterial, null, null);
    }

    public String decrypt(String encryptedData, KeyMaterial keyMaterial, Integer plainStart, Integer plainLength) {
        return decrypt(encryptedData, null, keyMaterial, plainStart, plainLength);
    }

    public String decrypt(String encryptedData, String policyAlgorithm, KeyMaterial keyMaterial,
                          Integer plainStart, Integer plainLength) {
        if (encryptedData == null) {
            return null;
        }
        try {
            String policyUid = DadpCryptoCore.extractPolicyUid(encryptedData);
            CryptoMaterial material = toMaterial(policyUid, policyAlgorithm, keyMaterial, null, plainStart, plainLength);
            log.trace("Local core decrypt start: extractedPolicyUid={}, algorithm={}, keyAlias={}, keyVersion={}, encryptedLength={}, encryptedPrefix={}, plainStart={}, plainLengthSegment={}",
                    material.getPolicyUid(), material.getAlgorithm(), material.getKeyAlias(), material.getKeyVersion(),
                    encryptedData.length(), WrapperLocalCryptoDebug.preview(encryptedData),
                    material.getPlainStart(), material.getPlainLength());
            String decrypted = core.decrypt(encryptedData, material);
            log.trace("Local core decrypt completed: extractedPolicyUid={}, decryptedLength={}, decryptedPrefix={}",
                    material.getPolicyUid(), decrypted != null ? decrypted.length() : 0, WrapperLocalCryptoDebug.preview(decrypted));
            return decrypted;
        } catch (com.dadp.crypto.core.UnsupportedCryptoMaterialException e) {
            throw new UnsupportedCryptoMaterialException(e.getMessage());
        } catch (com.dadp.crypto.core.CoreCryptoException e) {
            throw new WrapperCryptoException(e.getMessage(), e);
        }
    }

    public static String extractPolicyUid(String encryptedData) {
        return DadpCryptoCore.extractPolicyUid(encryptedData);
    }

    private static CryptoMaterial toMaterial(String policyUid, String policyAlgorithm, KeyMaterial keyMaterial,
                                             Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (keyMaterial == null) {
            throw new IllegalArgumentException("key material is required");
        }
        KeyMetadata metadata = keyMaterial.getMetadata();
        String effectiveAlgorithm = policyAlgorithm != null && !policyAlgorithm.trim().isEmpty()
                ? policyAlgorithm
                : metadata.getAlgorithm();
        return new CryptoMaterial(
                policyUid,
                metadata.getKeyAlias(),
                metadata.getKeyVersion(),
                metadata.getProvider(),
                effectiveAlgorithm,
                keyMaterial.getKeyData(),
                usePlain,
                plainStart,
                plainLength);
    }
}
