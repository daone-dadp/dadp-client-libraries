package com.dadp.wrapper.crypto;

/**
 * DADP 6.0.0 runtime execution key material for wrapper local crypto.
 */
public final class RuntimeExecutionKeyMaterial {

    private final String policyCode;
    private final int policyVersion;
    private final String keyAlias;
    private final int keyVersion;
    private final String providerType;
    private final String providerVendor;
    private final String algorithm;
    private final String materialType;
    private final String materialEncoding;
    private final String executionKeyBase64;
    private final int cacheTtlSeconds;
    private final long expiresAtMillis;

    public RuntimeExecutionKeyMaterial(String policyCode, int policyVersion,
                                       String keyAlias, int keyVersion,
                                       String providerType, String providerVendor,
                                       String algorithm, String materialType,
                                       String materialEncoding, String executionKeyBase64,
                                       int cacheTtlSeconds, long expiresAtMillis) {
        this.policyCode = require(policyCode, "policyCode");
        this.policyVersion = policyVersion;
        this.keyAlias = require(keyAlias, "keyAlias");
        this.keyVersion = keyVersion;
        this.providerType = providerType != null && !providerType.trim().isEmpty() ? providerType : "HUB";
        this.providerVendor = providerVendor;
        this.algorithm = algorithm != null && !algorithm.trim().isEmpty() ? algorithm : "AES_256";
        this.materialType = require(materialType, "materialType");
        this.materialEncoding = require(materialEncoding, "materialEncoding");
        this.executionKeyBase64 = require(executionKeyBase64, "executionKeyBase64");
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.expiresAtMillis = expiresAtMillis;
    }

    public PolicyMaterial toPolicyMaterial(String policyName) {
        return new PolicyMaterial(policyName, policyCode, policyVersion, keyAlias, keyVersion,
                algorithm, executionKeyBase64, expiresAtMillis, null, null, null);
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

    public String getProviderType() {
        return providerType;
    }

    public String getProviderVendor() {
        return providerVendor;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getMaterialType() {
        return materialType;
    }

    public String getMaterialEncoding() {
        return materialEncoding;
    }

    public String getExecutionKeyBase64() {
        return executionKeyBase64;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
