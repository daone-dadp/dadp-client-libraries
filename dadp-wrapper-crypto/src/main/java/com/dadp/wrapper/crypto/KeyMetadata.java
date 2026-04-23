package com.dadp.wrapper.crypto;

/**
 * Hub internal key metadata fields consumed by Engine and wrapper local crypto.
 */
public final class KeyMetadata {

    private final String keyAlias;
    private final int keyVersion;
    private final String provider;
    private final String configJson;
    private final String accessInfo;
    private final String algorithm;

    public KeyMetadata(String keyAlias, int keyVersion, String provider,
                       String configJson, String accessInfo, String algorithm) {
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
        this.provider = provider != null && !provider.trim().isEmpty() ? provider : "HUB";
        this.configJson = configJson;
        this.accessInfo = accessInfo;
        this.algorithm = algorithm;
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

    public String getConfigJson() {
        return configJson;
    }

    public String getAccessInfo() {
        return accessInfo;
    }

    public String getAlgorithm() {
        return algorithm;
    }
}
