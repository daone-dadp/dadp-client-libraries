package com.dadp.wrapper.crypto;

/**
 * Resolves key metadata and raw key material using the same Hub contract as Engine.
 */
public final class KeyMaterialResolver {

    private final HubInternalKeyClient hubClient;

    public KeyMaterialResolver(HubInternalKeyClient hubClient) {
        if (hubClient == null) {
            throw new IllegalArgumentException("hubClient is required");
        }
        this.hubClient = hubClient;
    }

    public KeyMaterial resolve(String keyAlias, int keyVersion) {
        KeyMetadata metadata = hubClient.fetchKeyMetadata(keyAlias, keyVersion);
        String provider = metadata.getProvider();
        if (provider != null && !"HUB".equalsIgnoreCase(provider) && !"DB".equalsIgnoreCase(provider)) {
            throw new UnsupportedCryptoMaterialException("Provider requires remote fallback: " + provider);
        }
        String keyData = hubClient.fetchKeyData(keyAlias, keyVersion);
        return new KeyMaterial(metadata, keyData);
    }
}
