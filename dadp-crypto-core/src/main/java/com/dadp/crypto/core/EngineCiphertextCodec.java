package com.dadp.crypto.core;

import java.util.Arrays;
import java.util.Base64;

public final class EngineCiphertextCodec {

    public static final String HUB_PREFIX = "hub:";
    public static final String PARTIAL_DELIMITER = "::ENC::";

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;

    private EngineCiphertextCodec() {
    }

    static String format(String policyUid, byte[] iv, byte[] ciphertext, byte[] tag) {
        byte[] combined = new byte[iv.length + ciphertext.length + tag.length];
        int offset = 0;
        System.arraycopy(iv, 0, combined, offset, iv.length);
        offset += iv.length;
        System.arraycopy(ciphertext, 0, combined, offset, ciphertext.length);
        offset += ciphertext.length;
        System.arraycopy(tag, 0, combined, offset, tag.length);
        return HUB_PREFIX + policyUid + ":" + Base64.getEncoder().encodeToString(combined);
    }

    static EncryptedPayload parse(String encryptedData) {
        if (encryptedData == null || encryptedData.trim().isEmpty()) {
            throw new CoreCryptoException("Encrypted data is empty");
        }

        String data = encryptedData;
        if (data.contains(PARTIAL_DELIMITER)) {
            data = data.substring(data.indexOf(PARTIAL_DELIMITER) + PARTIAL_DELIMITER.length());
        }

        if (!data.startsWith(HUB_PREFIX)) {
            throw new CoreCryptoException("Encrypted data must use hub:{policyUid}:{payload} format");
        }

        int payloadSeparator = data.indexOf(':', HUB_PREFIX.length());
        if (payloadSeparator <= HUB_PREFIX.length()) {
            throw new CoreCryptoException("Encrypted data has no policyUid");
        }

        String policyUid = data.substring(HUB_PREFIX.length(), payloadSeparator);
        String payloadBase64 = data.substring(payloadSeparator + 1);
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            throw new CoreCryptoException("Encrypted payload is not base64", e);
        }

        if (payload.length < IV_LENGTH + TAG_LENGTH) {
            throw new CoreCryptoException("Encrypted payload is too short: " + payload.length);
        }

        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] tag = Arrays.copyOfRange(payload, payload.length - TAG_LENGTH, payload.length);
        byte[] ciphertext = Arrays.copyOfRange(payload, IV_LENGTH, payload.length - TAG_LENGTH);
        return new EncryptedPayload(policyUid, iv, ciphertext, tag);
    }

    public static String extractPolicyUid(String encryptedData) {
        return parse(encryptedData).getPolicyUid();
    }

    public static boolean isPartial(String encryptedData) {
        return encryptedData != null && encryptedData.contains(PARTIAL_DELIMITER);
    }
}
