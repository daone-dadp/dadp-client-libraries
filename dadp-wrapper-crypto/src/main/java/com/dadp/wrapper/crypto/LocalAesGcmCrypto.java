package com.dadp.wrapper.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Engine-compatible local AES-GCM crypto for HUB/DB key material.
 */
public final class LocalAesGcmCrypto {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int POLICY_UID_LENGTH = 36;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom;

    public LocalAesGcmCrypto() {
        this(new SecureRandom());
    }

    LocalAesGcmCrypto(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plainText, String policyUid, KeyMaterial keyMaterial) {
        if (plainText == null) {
            return null;
        }
        validatePolicyUid(policyUid);
        SecretKeySpec key = key(keyMaterial);
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            int cipherLength = cipherAndTag.length - TAG_LENGTH;
            byte[] ciphertext = Arrays.copyOfRange(cipherAndTag, 0, cipherLength);
            byte[] tag = Arrays.copyOfRange(cipherAndTag, cipherLength, cipherAndTag.length);
            return format(policyUid, iv, ciphertext, tag);
        } catch (Exception e) {
            throw new WrapperCryptoException("Local AES-GCM encrypt failed: " + e.getMessage(), e);
        }
    }

    public String decrypt(String encryptedData, KeyMaterial keyMaterial) {
        if (encryptedData == null) {
            return null;
        }
        ParsedCiphertext parsed = parse(encryptedData);
        SecretKeySpec key = key(keyMaterial);
        byte[] cipherAndTag = new byte[parsed.ciphertext.length + parsed.tag.length];
        System.arraycopy(parsed.ciphertext, 0, cipherAndTag, 0, parsed.ciphertext.length);
        System.arraycopy(parsed.tag, 0, cipherAndTag, parsed.ciphertext.length, parsed.tag.length);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, parsed.iv));
            byte[] plain = cipher.doFinal(cipherAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new WrapperCryptoException("Local AES-GCM decrypt failed: " + e.getMessage(), e);
        }
    }

    public static String extractPolicyUid(String encryptedData) {
        return parse(encryptedData).policyUid;
    }

    private static String format(String policyUid, byte[] iv, byte[] ciphertext, byte[] tag) {
        byte[] policy = policyUid.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[policy.length + iv.length + ciphertext.length + tag.length];
        int pos = 0;
        System.arraycopy(policy, 0, combined, pos, policy.length);
        pos += policy.length;
        System.arraycopy(iv, 0, combined, pos, iv.length);
        pos += iv.length;
        System.arraycopy(ciphertext, 0, combined, pos, ciphertext.length);
        pos += ciphertext.length;
        System.arraycopy(tag, 0, combined, pos, tag.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static ParsedCiphertext parse(String encryptedData) {
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encryptedData);
        } catch (IllegalArgumentException e) {
            throw new WrapperCryptoException("Encrypted data is not base64", e);
        }
        if (combined.length < POLICY_UID_LENGTH + IV_LENGTH + TAG_LENGTH) {
            throw new WrapperCryptoException("Encrypted data is too short: " + combined.length);
        }
        String policyUid = new String(combined, 0, POLICY_UID_LENGTH, StandardCharsets.UTF_8);
        byte[] iv = Arrays.copyOfRange(combined, POLICY_UID_LENGTH, POLICY_UID_LENGTH + IV_LENGTH);
        byte[] tag = Arrays.copyOfRange(combined, combined.length - TAG_LENGTH, combined.length);
        byte[] ciphertext = Arrays.copyOfRange(combined, POLICY_UID_LENGTH + IV_LENGTH, combined.length - TAG_LENGTH);
        return new ParsedCiphertext(policyUid, iv, ciphertext, tag);
    }

    private static SecretKeySpec key(KeyMaterial material) {
        if (material == null) {
            throw new IllegalArgumentException("key material is required");
        }
        String algorithm = material.getMetadata().getAlgorithm();
        if (algorithm != null && !"A256GCM".equalsIgnoreCase(algorithm) && !"AES-256-GCM".equalsIgnoreCase(algorithm)) {
            throw new UnsupportedCryptoMaterialException("Algorithm requires remote fallback: " + algorithm);
        }
        byte[] keyBytes = Base64.getDecoder().decode(material.getKeyData());
        if (keyBytes.length != 32) {
            throw new UnsupportedCryptoMaterialException("AES-256-GCM requires 32-byte key material");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static void validatePolicyUid(String policyUid) {
        if (policyUid == null || policyUid.getBytes(StandardCharsets.UTF_8).length != POLICY_UID_LENGTH) {
            throw new IllegalArgumentException("policyUid must be a 36-byte UUID string");
        }
    }

    private static final class ParsedCiphertext {
        private final String policyUid;
        private final byte[] iv;
        private final byte[] ciphertext;
        private final byte[] tag;

        private ParsedCiphertext(String policyUid, byte[] iv, byte[] ciphertext, byte[] tag) {
            this.policyUid = policyUid;
            this.iv = iv;
            this.ciphertext = ciphertext;
            this.tag = tag;
        }
    }
}
