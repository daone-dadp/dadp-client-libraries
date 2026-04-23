package com.dadp.crypto.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class DadpCryptoCore {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom;

    public DadpCryptoCore() {
        this(new SecureRandom());
    }

    public DadpCryptoCore(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plainText, CryptoMaterial material) {
        if (plainText == null) {
            return null;
        }
        validateSupportedMaterial(material);

        if (canPartial(plainText, material)) {
            int start = material.getPlainStart().intValue();
            int length = material.getPlainLength().intValue();
            String plainSegment = plainText.substring(start, start + length);
            String toEncrypt = plainText.substring(0, start) + plainText.substring(start + length);
            return plainSegment + EngineCiphertextCodec.PARTIAL_DELIMITER + encryptWhole(toEncrypt, material);
        }

        return encryptWhole(plainText, material);
    }

    public String decrypt(String encryptedData, CryptoMaterial material) {
        if (encryptedData == null) {
            return null;
        }
        validateSupportedMaterial(material);

        String plainSegment = null;
        if (encryptedData.contains(EngineCiphertextCodec.PARTIAL_DELIMITER)) {
            int index = encryptedData.indexOf(EngineCiphertextCodec.PARTIAL_DELIMITER);
            plainSegment = encryptedData.substring(0, index);
        }

        String decrypted = decryptWhole(encryptedData, material);
        if (plainSegment == null) {
            return decrypted;
        }

        Integer start = material.getPlainStart();
        Integer length = material.getPlainLength();
        if (start == null || length == null || start.intValue() < 0 || length.intValue() < 0 || start.intValue() > decrypted.length()) {
            throw new CoreCryptoException("Partial decrypt requires valid plainStart/plainLength");
        }
        return decrypted.substring(0, start.intValue()) + plainSegment + decrypted.substring(start.intValue());
    }

    public static String extractPolicyUid(String encryptedData) {
        return EngineCiphertextCodec.extractPolicyUid(encryptedData);
    }

    private String encryptWhole(String plainText, CryptoMaterial material) {
        SecretKeySpec key = key(material);
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            int cipherLength = cipherAndTag.length - TAG_LENGTH;
            byte[] ciphertext = Arrays.copyOfRange(cipherAndTag, 0, cipherLength);
            byte[] tag = Arrays.copyOfRange(cipherAndTag, cipherLength, cipherAndTag.length);
            return EngineCiphertextCodec.format(material.getPolicyUid(), iv, ciphertext, tag);
        } catch (Exception e) {
            throw new CoreCryptoException("AES-GCM encrypt failed: " + e.getMessage(), e);
        }
    }

    private String decryptWhole(String encryptedData, CryptoMaterial material) {
        EncryptedPayload parsed = EngineCiphertextCodec.parse(encryptedData);
        SecretKeySpec key = key(material);
        byte[] cipherAndTag = new byte[parsed.getCiphertext().length + parsed.getTag().length];
        System.arraycopy(parsed.getCiphertext(), 0, cipherAndTag, 0, parsed.getCiphertext().length);
        System.arraycopy(parsed.getTag(), 0, cipherAndTag, parsed.getCiphertext().length, parsed.getTag().length);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, parsed.getIv()));
            byte[] plain = cipher.doFinal(cipherAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CoreCryptoException("AES-GCM decrypt failed: " + e.getMessage(), e);
        }
    }

    private static boolean canPartial(String plainText, CryptoMaterial material) {
        Integer plainStart = material.getPlainStart();
        Integer plainLength = material.getPlainLength();
        return Boolean.TRUE.equals(material.getUsePlain())
                && plainStart != null
                && plainLength != null
                && plainStart.intValue() >= 0
                && plainLength.intValue() > 0
                && plainStart.intValue() <= plainText.length()
                && plainStart.intValue() + plainLength.intValue() <= plainText.length();
    }

    private static SecretKeySpec key(CryptoMaterial material) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(material.getKeyData());
        } catch (IllegalArgumentException e) {
            throw new CoreCryptoException("Key material is not base64", e);
        }
        if (keyBytes.length != 32) {
            throw new UnsupportedCryptoMaterialException("AES-256-GCM requires 32-byte key material");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static void validateSupportedMaterial(CryptoMaterial material) {
        if (material == null) {
            throw new IllegalArgumentException("crypto material is required");
        }
        if (!"HUB".equalsIgnoreCase(material.getProvider()) && !"DB".equalsIgnoreCase(material.getProvider())) {
            throw new UnsupportedCryptoMaterialException("Provider requires remote fallback: " + material.getProvider());
        }
        String algorithm = material.getAlgorithm();
        if (algorithm != null && !"A256GCM".equalsIgnoreCase(algorithm) && !"AES-256-GCM".equalsIgnoreCase(algorithm)) {
            throw new UnsupportedCryptoMaterialException("Algorithm requires remote fallback: " + algorithm);
        }
    }
}
