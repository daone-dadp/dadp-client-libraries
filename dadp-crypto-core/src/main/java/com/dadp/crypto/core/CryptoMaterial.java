package com.dadp.crypto.core;

public final class CryptoMaterial {

    private final String policyUid;
    private final String keyAlias;
    private final int keyVersion;
    private final String provider;
    private final String algorithm;
    private final String keyData;
    private final Boolean usePlain;
    private final Integer plainStart;
    private final Integer plainLength;

    public CryptoMaterial(String policyUid, String keyAlias, int keyVersion, String provider, String algorithm,
                          String keyData, Boolean usePlain, Integer plainStart, Integer plainLength) {
        if (policyUid == null || policyUid.trim().isEmpty()) {
            throw new IllegalArgumentException("policyUid is required");
        }
        if (keyData == null || keyData.trim().isEmpty()) {
            throw new IllegalArgumentException("keyData is required");
        }
        this.policyUid = policyUid;
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
        this.provider = provider != null && !provider.trim().isEmpty() ? provider : "HUB";
        this.algorithm = algorithm != null && !algorithm.trim().isEmpty() ? algorithm : "A256GCM";
        this.keyData = keyData;
        this.usePlain = usePlain;
        this.plainStart = plainStart;
        this.plainLength = plainLength;
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

    public String getProvider() {
        return provider;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getKeyData() {
        return keyData;
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
