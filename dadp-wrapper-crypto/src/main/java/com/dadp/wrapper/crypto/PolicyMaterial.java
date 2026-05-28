package com.dadp.wrapper.crypto;

/**
 * Policy metadata required by wrapper-side local crypto.
 */
public final class PolicyMaterial {

    private final String policyName;
    private final String policyCode;
    private final int policyVersion;
    private final String keyAlias;
    private final int keyVersion;
    private final String algorithm;
    private final String executionKeyBase64;
    private final long expiresAtMillis;
    private final Boolean usePlain;
    private final Integer plainStart;
    private final Integer plainLength;

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
        this.executionKeyBase64 = executionKeyBase64;
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
        return executionKeyBase64;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
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
