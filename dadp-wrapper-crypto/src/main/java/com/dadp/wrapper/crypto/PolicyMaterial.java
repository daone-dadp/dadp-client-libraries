package com.dadp.wrapper.crypto;

import java.util.Arrays;

/**
 * Policy metadata required by wrapper-side local crypto.
 */
public final class PolicyMaterial implements AutoCloseable {

    private final String policyName;
    private final String policyCode;
    private final int policyVersion;
    private final String keyAlias;
    private final int keyVersion;
    private final String algorithm;
    private final char[] executionKeyBase64;
    private final long expiresAtMillis;
    private final Boolean usePlain;
    private final Integer plainStart;
    private final Integer plainLength;
    private volatile boolean keyClosed;

    public PolicyMaterial(String policyName, String policyCode, String keyAlias, int keyVersion,
                          String algorithm, Boolean usePlain, Integer plainStart, Integer plainLength) {
        this(policyName, policyCode, 1, keyAlias, keyVersion, algorithm, null, 0L,
                usePlain, plainStart, plainLength);
    }

    public PolicyMaterial(String policyName, String policyCode, int policyVersion, String keyAlias, int keyVersion,
                          String algorithm, String executionKeyBase64, long expiresAtMillis,
                          Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (policyCode == null || policyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("policyCode is required");
        }
        if (keyAlias == null || keyAlias.trim().isEmpty()) {
            throw new IllegalArgumentException("keyAlias is required");
        }
        if (keyVersion <= 0) {
            throw new IllegalArgumentException("keyVersion must be positive");
        }
        this.policyName = policyName;
        this.policyCode = policyCode;
        this.policyVersion = policyVersion > 0 ? policyVersion : 1;
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
        this.algorithm = algorithm;
        this.executionKeyBase64 = executionKeyBase64 != null ? executionKeyBase64.toCharArray() : null;
        this.expiresAtMillis = expiresAtMillis;
        this.usePlain = usePlain;
        this.plainStart = plainStart;
        this.plainLength = plainLength;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getPolicyCode() {
        return policyCode;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getExecutionKeyBase64() {
        if (keyClosed || executionKeyBase64 == null) {
            throw new IllegalStateException("policy execution key material is closed");
        }
        return new String(executionKeyBase64);
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }

    public boolean hasUsableExecutionKey(long nowMillis) {
        return !keyClosed
                && executionKeyBase64 != null
                && executionKeyBase64.length > 0
                && expiresAtMillis > 0L
                && nowMillis < expiresAtMillis;
    }

    @Override
    public void close() {
        if (executionKeyBase64 != null) {
            Arrays.fill(executionKeyBase64, '\0');
        }
        keyClosed = true;
    }

    public Boolean getUsePlain() {
        return usePlain;
    }

    public Integer getPlainStart() {
        return plainStart;
    }

    public Integer getPlainLength() {
        return plainLength;
    }
}
