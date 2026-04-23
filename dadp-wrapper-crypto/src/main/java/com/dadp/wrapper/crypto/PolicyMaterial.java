package com.dadp.wrapper.crypto;

/**
 * Policy metadata required by wrapper-side local crypto.
 */
public final class PolicyMaterial {

    private final String policyName;
    private final String policyUid;
    private final String keyAlias;
    private final int keyVersion;
    private final String algorithm;
    private final Boolean usePlain;
    private final Integer plainStart;
    private final Integer plainLength;

    public PolicyMaterial(String policyName, String policyUid, String keyAlias, int keyVersion,
                          String algorithm, Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (policyUid == null || policyUid.trim().isEmpty()) {
            throw new IllegalArgumentException("policyUid is required");
        }
        if (keyAlias == null || keyAlias.trim().isEmpty()) {
            throw new IllegalArgumentException("keyAlias is required");
        }
        if (keyVersion <= 0) {
            throw new IllegalArgumentException("keyVersion must be positive");
        }
        this.policyName = policyName;
        this.policyUid = policyUid;
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
        this.algorithm = algorithm;
        this.usePlain = usePlain;
        this.plainStart = plainStart;
        this.plainLength = plainLength;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getPolicyUid() {
        return policyUid;
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
